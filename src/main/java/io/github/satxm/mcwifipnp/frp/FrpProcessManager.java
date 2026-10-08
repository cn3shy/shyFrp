package io.github.satxm.mcwifipnp.frp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.satxm.mcwifipnp.MCWiFiPnPUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * frpc 进程管理器（客户端单例）。
 * <p>
 * 职责：启动/停止 frpc 进程；后台逐行读取输出并分发：
 * <ul>
 * <li>追加写入 logs/frpc.log（全量）；</li>
 * <li>维护环形缓冲（供界面日志区展示）；</li>
 * <li>识别关键事件推送到游戏聊天栏（带去重与节流）。</li>
 * </ul>
 */
public class FrpProcessManager {
	/** 环形缓冲保留的最大行数。 */
	private static final int MAX_BUFFER_LINES = 500;
	/** 连续重复聊天消息的去重窗口（毫秒）。 */
	private static final long CHAT_DEDUP_WINDOW_MS = 500;
	/** 聊天推送节流窗口（毫秒）与窗口内最大条数。 */
	private static final long CHAT_WINDOW_MS = 1000;
	private static final int CHAT_MAX_PER_WINDOW = 3;

	/** 命中这些关键字（不区分大小写）的输出行会被推送到游戏聊天栏。 */
	private static final List<String> KEY_EVENT_KEYWORDS = List.of(
			"error", "warn", "fail", "success", "start", "stop", "close",
			"connect", "disconnect", "reconnect", "login", "tunnel",
			"错误", "失败", "成功", "启动", "停止", "连接", "断开", "重连", "隧道", "退出");

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final FrpProcessManager INSTANCE = new FrpProcessManager();

	public static FrpProcessManager getInstance() {
		return INSTANCE;
	}

	/** 环形缓冲与版本号（版本号每次追加自增，供界面判断是否有新输出）。 */
	private final Deque<String> buffer = new ArrayDeque<>();
	private long version;

	private volatile Process process;
	private volatile String lastError;

	private String lastChatLine;
	private long lastChatTime;
	private long chatWindowStart;
	private int chatWindowCount;

	private FrpProcessManager() {
		// 游戏进程退出时兜底清理，避免残留孤儿进程
		Runtime.getRuntime().addShutdownHook(new Thread(this::stopBlocking, "mcwifipnp-frpc-shutdown"));
	}

	public synchronized boolean isRunning() {
		Process p = this.process;
		return p != null && p.isAlive();
	}

	/** 最近一次启动失败的原因，成功启动或手动清除后为 null。 */
	public synchronized String getLastError() {
		return this.lastError;
	}

	public synchronized void clearLastError() {
		this.lastError = null;
	}

	/**
	 * 启动 frpc。
	 *
	 * @return null 表示启动成功；否则为可展示给用户的错误描述。
	 */
	public synchronized String start(FrpConfig cfg) {
		if (this.isRunning()) {
			return null;
		}

		String frpcPath = cfg.frpcPath == null ? "" : cfg.frpcPath.trim();
		String token = cfg.token == null ? "" : cfg.token.trim();
		String tunnelId = cfg.tunnelId == null ? "" : cfg.tunnelId.trim();

		if (frpcPath.isEmpty() || !Files.isExecutable(Path.of(frpcPath))) {
			this.lastError = "frpc 路径无效：" + frpcPath;
			return this.lastError;
		}
		if (token.isEmpty() || tunnelId.isEmpty()) {
			this.lastError = "访问密钥 / 隧道 ID 不能为空";
			return this.lastError;
		}

		this.lastError = null;
		try {
			ProcessBuilder pb = new ProcessBuilder(frpcPath, "-f", token + ":" + tunnelId);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			this.process = p;
			appendLine("[FRP] frpc 已启动（" + frpcPath + "）");
			pushChat("[FRP] frpc 已启动");

			Thread reader = new Thread(() -> readLoop(p), "mcwifipnp-frpc-reader");
			reader.setDaemon(true);
			reader.start();
			return null;
		} catch (IOException e) {
			this.process = null;
			this.lastError = "无法启动 frpc：" + e.getMessage();
			MCWiFiPnPUnit.LOGGER.warn("Unable to start frpc", e);
			return this.lastError;
		}
	}

	/** 请求停止 frpc（异步，不阻塞调用线程）。 */
	public synchronized void stop() {
		Process p = this.process;
		if (p == null) {
			return;
		}
		appendLine("[FRP] 正在停止 frpc…");
		p.destroy();
		Thread watchdog = new Thread(() -> forceKillIfNeeded(p), "mcwifipnp-frpc-stopper");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** 阻塞式停止（仅 shutdown hook 使用）。 */
	private void stopBlocking() {
		Process p = this.process;
		if (p == null) {
			return;
		}
		p.destroy();
		try {
			if (!p.waitFor(2, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void forceKillIfNeeded(Process p) {
		try {
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	// ---- 环形缓冲访问（供界面） ----

	public synchronized long getVersion() {
		return this.version;
	}

	public synchronized List<String> getRecentLines(int maxLines) {
		int size = this.buffer.size();
		if (size <= maxLines) {
			return new ArrayList<>(this.buffer);
		}
		List<String> all = new ArrayList<>(this.buffer);
		return new ArrayList<>(all.subList(size - maxLines, size));
	}

	// ---- 内部：输出读取与分发 ----

	private void readLoop(Process p) {
		BufferedWriter writer = openLogWriter();

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				appendLine(line);
				if (writer != null) {
					try {
						writer.write(line);
						writer.newLine();
						writer.flush();
					} catch (IOException ignored) {
						// 日志文件写失败不中断输出读取
					}
				}
				pushKeyEvent(line);
			}
		} catch (IOException e) {
			appendLine("[FRP] 读取 frpc 输出失败：" + e.getMessage());
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException ignored) {
				}
			}
		}

		int exitCode = -1;
		try {
			exitCode = p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (this) {
			if (this.process == p) {
				this.process = null;
			}
		}

		String exitLine = "[FRP] frpc 已退出（退出码 " + exitCode + "）";
		appendLine(exitLine);
		pushChat(exitLine);
	}

	private BufferedWriter openLogWriter() {
		Path logFile = logFilePath();
		try {
			Files.createDirectories(logFile.getParent());
			BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			writer.write("===== frpc 会话开始 " + LocalDateTime.now().format(TIME_FORMAT) + " =====");
			writer.newLine();
			writer.flush();
			return writer;
		} catch (IOException e) {
			MCWiFiPnPUnit.LOGGER.warn("Unable to open frpc log file", e);
			return null;
		}
	}

	private void appendLine(String line) {
		synchronized (this) {
			this.buffer.addLast(line);
			while (this.buffer.size() > MAX_BUFFER_LINES) {
				this.buffer.removeFirst();
			}
			this.version++;
		}
	}

	private void pushKeyEvent(String line) {
		String lower = line.toLowerCase(Locale.ROOT);
		for (String keyword : KEY_EVENT_KEYWORDS) {
			if (lower.contains(keyword)) {
				pushChat("[FRP] " + line);
				return;
			}
		}
	}

	private void pushChat(String message) {
		if (!FrpConfig.get().pushChatEvents) {
			return;
		}

		long now = System.currentTimeMillis();
		synchronized (this) {
			// 连续重复消息去重
			if (message.equals(this.lastChatLine) && now - this.lastChatTime < CHAT_DEDUP_WINDOW_MS) {
				return;
			}
			// 节流：窗口内最多推送 N 条，避免刷屏
			if (now - this.chatWindowStart >= CHAT_WINDOW_MS) {
				this.chatWindowStart = now;
				this.chatWindowCount = 0;
			}
			if (this.chatWindowCount >= CHAT_MAX_PER_WINDOW) {
				return;
			}
			this.chatWindowCount++;
			this.lastChatLine = message;
			this.lastChatTime = now;
		}

		Minecraft.getInstance().execute(() -> {
			Minecraft mc = Minecraft.getInstance();
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
		});
	}

	private static Path logFilePath() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("frpc.log");
	}
}
