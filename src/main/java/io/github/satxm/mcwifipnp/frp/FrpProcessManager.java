package io.github.satxm.mcwifipnp.frp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.satxm.mcwifipnp.MCWiFiPnPUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * frpc 进程管理器（客户端单例）。
 * <p>
 * 职责：启动/停止 frpc 进程；后台逐行读取输出，写 logs/frpc.log（全量落盘），
 * 并在 config/shyfrp.json 的 log-to-chat 打开时把每一行原样推送到游戏聊天栏。
 */
public class FrpProcessManager {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final FrpProcessManager INSTANCE = new FrpProcessManager();

	public static FrpProcessManager getInstance() {
		return INSTANCE;
	}

	private volatile Process process;
	private volatile String lastError;

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
	 * 启动 frpc。会先重新读取 config/shyfrp.json，手改配置无需重启游戏。
	 *
	 * @return null 表示启动成功；否则为可展示给用户的错误描述。
	 */
	public synchronized String start() {
		if (this.isRunning()) {
			return null;
		}

		FrpConfig cfg = FrpConfig.reload();
		String exe = cfg.exe == null ? "" : cfg.exe.trim();
		List<String> args = cfg.parsedArgs();
		Path configPath = FrpConfig.getConfigPath();

		if (!isExecutable(exe)) {
			this.lastError = "frpc 路径无效：" + exe + "（请在 " + configPath + " 中配置 exe）";
			return this.lastError;
		}
		if (args.isEmpty()) {
			this.lastError = "启动参数为空（请在 " + configPath + " 中配置 args）";
			return this.lastError;
		}

		this.lastError = null;
		try {
			List<String> command = new ArrayList<>(args.size() + 1);
			command.add(exe);
			command.addAll(args);

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			this.process = p;
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

	private static boolean isExecutable(final String exe) {
		if (exe.isEmpty()) {
			return false;
		}
		try {
			return Files.isExecutable(Path.of(exe));
		} catch (InvalidPathException e) {
			return false;
		}
	}

	/** 请求停止 frpc（异步，不阻塞调用线程）。 */
	public synchronized void stop() {
		Process p = this.process;
		if (p == null) {
			return;
		}
		pushChat("[FRP] 正在停止 frpc…");
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

	private static void forceKillIfNeeded(final Process p) {
		try {
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	// ---- 内部：输出读取与分发 ----

	private void readLoop(final Process p) {
		BufferedWriter writer = openLogWriter();

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (writer != null) {
					try {
						writer.write(line);
						writer.newLine();
						writer.flush();
					} catch (IOException ignored) {
						// 日志文件写失败不中断输出读取
					}
				}
				pushChat("[FRP] " + line);
			}
		} catch (IOException e) {
			pushChat("[FRP] 读取 frpc 输出失败：" + e.getMessage());
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

		pushChat("[FRP] frpc 已退出（退出码 " + exitCode + "）");
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

	private void pushChat(final String message) {
		if (!FrpConfig.get().logToChatEnabled()) {
			return;
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
