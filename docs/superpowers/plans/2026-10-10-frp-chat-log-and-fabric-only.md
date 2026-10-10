# FRP 日志转聊天 + 配置外置 + 仅 Fabric 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 frpc 的全部输出实时进聊天栏（`/shyfrp log true|false` 开关）、把 exe 与启动参数挪到 `config/shyfrp.json` 并在启动前热重载、界面只剩自动开启开关与启停按钮，同时把项目精简为仅 Fabric 且产物改名为 `shyfrp-<version>.jar`。

**Architecture:** `FrpConfig` 变成单一配置源（`config/shyfrp.json`，含旧文件一次性迁移与 `reload()`）；`FrpProcessManager.start()` 改为无参并在内部 `reload()`，用 `exe + parsedArgs()` 组 argv，逐行全量转发；`/shyfrp` 走 Fabric 客户端命令 API（`ClientCommandRegistrationCallback`），共享逻辑放 `FrpLogCommand`，注册胶水放 Fabric 入口类；构建侧删除 forge/neoforge 模块与 `publish.yaml`。

**Tech Stack:** Java 25、Fabric Loom 1.17、fabric-api 0.161.0+26.3（`fabric-command-api-v2`）、Gson、Gradle 9.5.1。

**依据 spec：** `docs/superpowers/specs/2026-10-10-frp-chat-log-and-fabric-only-design.md`

**关于测试：** 本仓库没有 test 源集，spec 第 12 节已明确本次不引入测试框架。因此每个任务的验证步骤是「编译 + 签名核对」而不是单元测试：本机命令行 `./gradlew` 无法保证能拉到依赖，所以每步先做能做的静态核对，最终以 Task 5 的构建/CI 为准。

---

### Task 1: FRP 配置层与进程层重构（含界面适配）

三个文件相互依赖（`FrpConfig` 改字段 → `FrpProcessManager.start()` 改签名 → 界面调用点跟进），必须一起改、一起提交，否则中间态编译不过。

**Files:**
- Modify（整体重写）: `src/main/java/io/github/satxm/mcwifipnp/frp/FrpConfig.java`
- Modify（整体重写）: `src/main/java/io/github/satxm/mcwifipnp/frp/FrpProcessManager.java`
- Modify: `src/main/java/io/github/satxm/mcwifipnp/client/WorldOptionsScreenNew.java`（第 103-116、497-666、694-698 行附近）
- Modify: `src/main/resources/assets/mcwifipnp/lang/zh_cn.json`、`en_us.json`（第 55-60 行附近）

- [ ] **Step 1: 重写 `FrpConfig.java`**

整体替换为：

```java
package io.github.satxm.mcwifipnp.frp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import io.github.satxm.mcwifipnp.MCWiFiPnPUnit;
import net.minecraft.client.Minecraft;

/**
 * shyFrp 全局配置，所有世界共用。
 * 存储位置：&lt;游戏目录&gt;/config/shyfrp.json
 * <p>
 * 仅客户端使用（frpc 是客户端功能）。exe 与 args 由用户手改文件，
 * 每次启动 frpc 前都会重新读盘，因此改完不用重启游戏。
 */
public class FrpConfig {
	/** frpc 可执行文件路径。 */
	@SerializedName("exe")
	public String exe = "";

	/** frpc 启动参数（按空白切分成 argv，不支持带空格的单个参数）。 */
	@SerializedName("args")
	public String args = "";

	/** 发布局域网后是否自动启动 frpc。可空：手写配置缺字段时按默认 true 处理。 */
	@SerializedName("auto-start")
	public Boolean autoStart = Boolean.TRUE;

	/** 是否把 frpc 输出推送到游戏聊天栏。可空：手写配置缺字段时按默认 true 处理。 */
	@SerializedName("log-to-chat")
	public Boolean logToChat = Boolean.TRUE;

	/** 当前配置文件名。 */
	private static final String FILE_NAME = "shyfrp.json";
	/** 旧版配置文件名（已废弃，仅用于一次性迁移）。 */
	private static final String LEGACY_FILE_NAME = "mcwifipnp-frp.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static FrpConfig instance;

	private FrpConfig() {
	}

	/** 获取全局配置实例（首次访问时从磁盘读取）。 */
	public static synchronized FrpConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** 强制重新读盘并替换全局实例（启动 frpc 前调用）。 */
	public static synchronized FrpConfig reload() {
		instance = load();
		return instance;
	}

	public static Path getConfigPath() {
		return configDir().resolve(FILE_NAME);
	}

	private static Path configDir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config");
	}

	/** 自动启动开关（缺字段时为 true）。 */
	public boolean autoStartEnabled() {
		return this.autoStart == null || this.autoStart.booleanValue();
	}

	public void setAutoStart(final boolean value) {
		this.autoStart = Boolean.valueOf(value);
	}

	/** 聊天栏日志开关（缺字段时为 true）。 */
	public boolean logToChatEnabled() {
		return this.logToChat == null || this.logToChat.booleanValue();
	}

	public void setLogToChat(final boolean value) {
		this.logToChat = Boolean.valueOf(value);
	}

	/** 启动参数，已按空白切分。 */
	public List<String> parsedArgs() {
		String raw = this.args == null ? "" : this.args.trim();
		if (raw.isEmpty()) {
			return List.of();
		}
		return Arrays.stream(raw.split("\\s+")).filter(part -> !part.isEmpty()).toList();
	}

	private static FrpConfig load() {
		Path path = getConfigPath();
		if (Files.exists(path)) {
			FrpConfig cfg = read(path, FrpConfig.class);
			if (cfg != null) {
				cfg.normalize();
				return cfg;
			}
			return new FrpConfig();
		}

		// 首次运行：优先从旧配置迁移，否则生成一份默认文件
		FrpConfig migrated = migrateLegacy();
		FrpConfig cfg = migrated != null ? migrated : new FrpConfig();
		cfg.normalize();
		cfg.save();
		return cfg;
	}

	/** 从旧版 mcwifipnp-frp.json 迁移；旧文件不存在或不可读时返回 null。旧文件不会被改写或删除。 */
	private static FrpConfig migrateLegacy() {
		Path legacy = configDir().resolve(LEGACY_FILE_NAME);
		if (!Files.exists(legacy)) {
			return null;
		}
		LegacyConfig old = read(legacy, LegacyConfig.class);
		if (old == null) {
			return null;
		}

		FrpConfig cfg = new FrpConfig();
		cfg.exe = old.frpcPath == null ? "" : old.frpcPath;
		cfg.autoStart = Boolean.valueOf(old.autoStart);
		cfg.logToChat = Boolean.valueOf(old.pushChatEvents);

		String token = old.token == null ? "" : old.token.trim();
		String tunnelId = old.tunnelId == null ? "" : old.tunnelId.trim();
		if (!token.isEmpty() && !tunnelId.isEmpty()) {
			cfg.args = "-f " + token + ":" + tunnelId;
		}

		MCWiFiPnPUnit.LOGGER.info("Migrated legacy {} to {}", LEGACY_FILE_NAME, FILE_NAME);
		return cfg;
	}

	private static <T> T read(final Path path, final Class<T> type) {
		try {
			return GSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), type);
		} catch (IOException | JsonParseException e) {
			MCWiFiPnPUnit.LOGGER.warn("Unable to read FRP config: " + path, e);
			return null;
		}
	}

	/** 补齐 Gson 反序列化时缺失的字段（手写配置可能只写了部分字段）。 */
	private void normalize() {
		this.exe = this.exe == null ? "" : this.exe;
		this.args = this.args == null ? "" : this.args;
		if (this.autoStart == null) {
			this.autoStart = Boolean.TRUE;
		}
		if (this.logToChat == null) {
			this.logToChat = Boolean.TRUE;
		}
	}

	/** 保存到磁盘。 */
	public void save() {
		Path path = getConfigPath();
		try {
			Files.createDirectories(path.getParent());
			Files.write(path, GSON.toJson(this).getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			MCWiFiPnPUnit.LOGGER.warn("Unable to save FRP config", e);
		}
	}

	/** 旧版配置文件结构（仅迁移用）。 */
	private static class LegacyConfig {
		@SerializedName("frpc-path")
		String frpcPath;
		@SerializedName("access-token")
		String token;
		@SerializedName("tunnel-id")
		String tunnelId;
		@SerializedName("auto-start")
		boolean autoStart = true;
		@SerializedName("push-chat-events")
		boolean pushChatEvents = true;
	}
}
```

- [ ] **Step 2: 重写 `FrpProcessManager.java`**

整体替换为：

```java
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
```

- [ ] **Step 3: 精简界面 FRP 分区**

`WorldOptionsScreenNew.java`：

1) 删除字段（第 105 行与第 107-115 行的 FRP 部分），只保留：

```java
	// ---- FRP 穿透分区 ----
	private CycleButton<Boolean> frpAutoStartButton;
	private Button frpToggleButton;
	private StringWidget frpStatusWidget;
	private boolean frpLastRunning;
	private String frpLastError;
```

2) `frpOptions(final LinearLayout content)` 方法体替换为：

```java
	private void frpOptions(final LinearLayout content) {
		GridLayout grid = content.addChild(new GridLayout());
		grid.defaultCellSetting().alignHorizontallyCenter();
		RowHelper rowHelper = grid.columnSpacing(8).rowSpacing(4).createRowHelper(2);
		rowHelper.defaultCellSetting().alignHorizontallyCenter();

		// 分区标题
		rowHelper.addChild(FocusableTextWidget
				.builder(Component.translatable("mcwifipnp.frp.title").withStyle(ChatFormatting.UNDERLINE,
						ChatFormatting.BOLD), this.font)
				.alwaysShowBorder(false).backgroundFill(BackgroundFill.ON_FOCUS).build(), 2);

		// 自动启动开关 + 启动/停止按钮
		this.frpAutoStartButton = CycleButton.onOffBuilder(FrpConfig.get().autoStartEnabled())
				.withTooltip(state -> Tooltip.create(Component.translatable("mcwifipnp.frp.autoStart.info")))
				.create(Component.translatable("mcwifipnp.frp.autoStart"), (cycleButton, autoStart) -> {
					FrpConfig.get().setAutoStart(autoStart);
					FrpConfig.get().save();
				});
		rowHelper.addChild(this.frpAutoStartButton);

		this.frpToggleButton = rowHelper
				.addChild(Button.builder(Component.translatable("mcwifipnp.frp.start"), button -> this.toggleFrpc()).build());

		// 状态指示
		this.frpStatusWidget = rowHelper.addChild(new StringWidget(Component.empty(), this.font), 2);

		// 配置文件提示（exe 与 args 在文件里改）
		rowHelper.addChild(new StringWidget(
				Component.translatable("mcwifipnp.frp.configHint").withStyle(ChatFormatting.GRAY), this.font), 2);
	}
```

3) `toggleFrpc()` 里的 `manager.start(FrpConfig.get())` 改为 `manager.start()`。

4) `startFrpcIfNeeded(...)` 里的 `FrpConfig frpCfg = FrpConfig.get();` 与 `if (!frpCfg.autoStart ...)` 改为：

```java
	private void startFrpcIfNeeded(final @Nullable IntegratedServer singleplayerServer) {
		if (!FrpConfig.get().autoStartEnabled() || singleplayerServer == null) {
			return;
		}
		if (cfg.multiplayerScope != MultiplayerScope.LAN || !singleplayerServer.isPublished()) {
			return;
		}
		FrpProcessManager manager = FrpProcessManager.getInstance();
		if (manager.isRunning()) {
			return;
		}
		String error = manager.start();
		if (error != null) {
			this.sendFrpMessage(Component.literal("[FRP] " + error).withStyle(ChatFormatting.RED));
		}
	}
```

5) `updateFrpWidgets(final boolean force)` 去掉参数与日志刷新分支：

```java
	/** 刷新 FRP 状态文本与启停按钮。 */
	private void updateFrpWidgets() {
		if (this.frpStatusWidget == null) {
			return;
		}
		FrpProcessManager manager = FrpProcessManager.getInstance();

		boolean running = manager.isRunning();
		String lastError = manager.getLastError();
		if (running != this.frpLastRunning || !Objects.equals(lastError, this.frpLastError)) {
			this.frpLastRunning = running;
			this.frpLastError = lastError;
			if (running) {
				this.frpStatusWidget.setMessage(
						Component.translatable("mcwifipnp.frp.status", Component.translatable("mcwifipnp.frp.status.running")
								.withStyle(ChatFormatting.GREEN)));
			} else if (lastError != null) {
				this.frpStatusWidget.setMessage(
						Component.translatable("mcwifipnp.frp.status", Component.literal(lastError)
								.withStyle(ChatFormatting.RED)));
			} else {
				this.frpStatusWidget.setMessage(
						Component.translatable("mcwifipnp.frp.status", Component.translatable("mcwifipnp.frp.status.stopped")
								.withStyle(ChatFormatting.GRAY)));
			}
			this.frpToggleButton.setMessage(running ? Component.translatable("mcwifipnp.frp.stop")
					: Component.translatable("mcwifipnp.frp.start"));
		}
	}
```

6) 三处调用点 `this.updateFrpWidgets(true)` / `this.updateFrpWidgets(false)` 全部改为 `this.updateFrpWidgets()`。

7) 检查 `java.util.List` 是否还被本文件使用；若已无引用则删除 `import java.util.List;`（`Objects` 仍在用，保留）。

- [ ] **Step 4: 更新语言文件**

`zh_cn.json` 中删除这 6 行：`mcwifipnp.frp.path`、`mcwifipnp.frp.path.info`、`mcwifipnp.frp.token`、`mcwifipnp.frp.token.info`、`mcwifipnp.frp.tunnelId`、`mcwifipnp.frp.tunnelId.info`，并新增一行（注意前后逗号，JSON 不允许尾逗号）：

```json
  "mcwifipnp.frp.configHint": "配置文件：config/shyfrp.json（exe 与 args）",
```

`en_us.json` 同样删 6 行，新增：

```json
  "mcwifipnp.frp.configHint": "Config file: config/shyfrp.json (exe and args)",
```

- [ ] **Step 5: 编译核对**

核对改动文件里不再出现 `setFormatter`/`frpcPathEdit`/`frpTokenEdit`/`frpTunnelIdEdit`/`getRecentLines`/`getVersion`/`FRP_LOG_LINES`：

```bash
grep -rn "frpcPathEdit\|frpTokenEdit\|frpTunnelIdEdit\|getRecentLines\|getVersion()\|FRP_LOG_LINES\|updateFrpWidgets(true)\|updateFrpWidgets(false)" src/ fabric/
```

Expected: 无输出。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/satxm/mcwifipnp/frp/FrpConfig.java \
        src/main/java/io/github/satxm/mcwifipnp/frp/FrpProcessManager.java \
        src/main/java/io/github/satxm/mcwifipnp/client/WorldOptionsScreenNew.java \
        src/main/resources/assets/mcwifipnp/lang/zh_cn.json \
        src/main/resources/assets/mcwifipnp/lang/en_us.json
git commit -m "refactor(frp): 配置迁移到 config/shyfrp.json 并精简界面

- FrpConfig 改为 exe/args/auto-start/log-to-chat，启动前 reload()，旧 mcwifipnp-frp.json 一次性迁移且不改写
- FrpProcessManager.start() 无参、用 exe + args 组 argv，去掉关键词过滤/去重/节流与环形缓冲，改为逐行全量转发（受 log-to-chat 控制）
- 界面删除 frpc 路径/访问密钥/隧道 ID 输入框与 8 行日志区，保留开关、启停按钮、状态行并加配置文件提示"
```

---

### Task 2: `/shyfrp` 客户端命令

**Files:**
- Create: `src/main/java/io/github/satxm/mcwifipnp/frp/FrpLogCommand.java`
- Modify: `fabric/src/main/java/io/github/satxm/mcwifipnp/MCWiFiPnP.java`
- Modify: `src/main/resources/assets/mcwifipnp/lang/zh_cn.json`、`en_us.json`

- [ ] **Step 1: 新建 `FrpLogCommand.java`**

```java
package io.github.satxm.mcwifipnp.frp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * /shyfrp 客户端命令的共享逻辑（与具体加载器无关，注册胶水在各平台入口类里）。
 */
public final class FrpLogCommand {
	private FrpLogCommand() {
	}

	/** 设置聊天栏日志开关并保存，返回回显文本。 */
	public static Component setLogToChat(final boolean enabled) {
		FrpConfig cfg = FrpConfig.get();
		cfg.setLogToChat(enabled);
		cfg.save();
		return stateMessage(enabled);
	}

	/** 当前开关状态回显。 */
	public static Component currentState() {
		return stateMessage(FrpConfig.get().logToChatEnabled());
	}

	/** 用法说明。 */
	public static Component usage() {
		return Component.translatable("mcwifipnp.frp.command.usage", FrpConfig.getConfigPath().toString())
				.withStyle(ChatFormatting.GRAY);
	}

	private static Component stateMessage(final boolean enabled) {
		return Component.translatable("mcwifipnp.frp.command.log",
				Component.translatable(enabled ? "mcwifipnp.frp.command.log.on" : "mcwifipnp.frp.command.log.off")
						.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY));
	}
}
```

- [ ] **Step 2: 在 Fabric 入口注册客户端命令**

`fabric/src/main/java/io/github/satxm/mcwifipnp/MCWiFiPnP.java` 整体替换为：

```java
package io.github.satxm.mcwifipnp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;

import io.github.satxm.mcwifipnp.frp.FrpLogCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class MCWiFiPnP implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STOPPING.register(MCWiFiPnPUnit::onServerStopping);
		ServerLifecycleEvents.SERVER_STARTING.register(MCWiFiPnPUnit::onServerStarting);
	}

	@Override
	public void onInitializeClient() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MCWiFiPnPUnit.registerCommands(dispatcher, false);
		});

		// 客户端命令：/shyfrp —— 单机/局域网存档下无需开作弊或 OP
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("shyfrp")
					.executes(context -> {
						context.getSource().sendFeedback(FrpLogCommand.usage());
						context.getSource().sendFeedback(FrpLogCommand.currentState());
						return Command.SINGLE_SUCCESS;
					})
					.then(ClientCommands.literal("log")
							.executes(context -> {
								context.getSource().sendFeedback(FrpLogCommand.currentState());
								return Command.SINGLE_SUCCESS;
							})
							.then(ClientCommands.argument("value", BoolArgumentType.bool())
									.executes(context -> {
										boolean value = BoolArgumentType.getBool(context, "value");
										context.getSource().sendFeedback(FrpLogCommand.setLogToChat(value));
										return Command.SINGLE_SUCCESS;
									}))));
		});
	}

	@Override
	public void onInitializeServer() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MCWiFiPnPUnit.registerCommands(dispatcher, true);
		});
	}
}
```

- [ ] **Step 3: 语言文件新增命令回显 key**

`zh_cn.json` 在 `mcwifipnp.frp.configHint` 之后新增：

```json
  "mcwifipnp.frp.command.usage": "用法：/shyfrp log true|false —— 配置文件：%s",
  "mcwifipnp.frp.command.log": "FRP 日志转发到聊天栏：%s",
  "mcwifipnp.frp.command.log.on": "已开启",
  "mcwifipnp.frp.command.log.off": "已关闭",
```

`en_us.json` 对应新增：

```json
  "mcwifipnp.frp.command.usage": "Usage: /shyfrp log true|false -- Config file: %s",
  "mcwifipnp.frp.command.log": "FRP log to chat: %s",
  "mcwifipnp.frp.command.log.on": "enabled",
  "mcwifipnp.frp.command.log.off": "disabled",
```

- [ ] **Step 4: 核对客户端命令 API 签名**

用真实 jar 核对（本机已下载在 `C:\Users\asus\AppData\Local\Temp\shyfrp-verify\`，缺失时可重新从 `maven.fabricmc.net` 拉 `fabric-api-0.161.0+26.3.jar` 并解出 `META-INF/jars/fabric-command-api-v2-*.jar`）：

```bash
cd /c/Users/asus/AppData/Local/Temp/shyfrp-verify
/c/DEV/jdk/jdk25/bin/javap -cp cmdapi.jar \
  net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback \
  net.fabricmc.fabric.api.client.command.v2.ClientCommands \
  net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
```

Expected: 能看到 `EVENT`、`register(CommandDispatcher<FabricClientCommandSource>, CommandBuildContext)`、`ClientCommands.literal(String)`、`sendFeedback(Component)`。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/github/satxm/mcwifipnp/frp/FrpLogCommand.java \
        fabric/src/main/java/io/github/satxm/mcwifipnp/MCWiFiPnP.java \
        src/main/resources/assets/mcwifipnp/lang/zh_cn.json \
        src/main/resources/assets/mcwifipnp/lang/en_us.json
git commit -m "feat(frp): 新增 /shyfrp log 客户端命令控制聊天栏日志"
```

---

### Task 3: 精简为仅 Fabric + 产物改名 + CI 调整

**Files:**
- Delete: `forge/`、`neoforge/`、`.github/workflows/publish.yaml`
- Modify: `settings.gradle`、`gradle.properties`、`fabric/build.gradle`、`.github/workflows/build.yml`、`.github/workflows/release.yml`

- [ ] **Step 1: 删除两个平台模块与 publish 工作流**

```bash
git rm -r --quiet forge neoforge
git rm --quiet .github/workflows/publish.yaml
```

- [ ] **Step 2: 清理 `settings.gradle`**

删除这两个 maven 仓库块：

```groovy
    maven {
      name = 'MinecraftForge'
      url = 'https://maven.minecraftforge.net/'
    }
    maven {
      name = 'NeoForge'
      url = 'https://maven.neoforged.net/#/releases'
    }
```

删除文件末尾的两行：

```groovy
include("forge")
include("neoforge")
```

保留 `include("fabric")` 与其余仓库（Fabric、Quilt、Minecraft libraries、Sponge、mavenCentral、gradlePluginPortal）。

- [ ] **Step 3: 清理 `gradle.properties`**

删除以下 7 行（含各自上方的注释行）：

```properties
net.minecraftforge.gradle.merge-source-sets=true
forge_version = 66.0.2
neo_version = 26.3.0.8-beta
forge_version_range = [66,)
forge_loader_version_range = [66,)
neo_version_range = [26.3.0.0-beta,)
neo_loader_version_range = [2,)
```

以及注释 `# Forge Properties ...`、`# NeoForge Properties ...`、`# Forge`、`# NeoForge` 等只服务于它们的注释行。

- [ ] **Step 4: 产物改名（`fabric/build.gradle`）**

第 7-9 行：

```groovy
version = project.mod_version + "-" + project.minecraft_version + "-fabric"
group = project.mod_group_id
base.archivesName = project.mod_id
```

改为：

```groovy
version = project.mod_version
group = project.mod_group_id
base.archivesName = "shyfrp"
```

- [ ] **Step 5: 调整 `build.yml`**

`name: mcwifipnp-artifacts` 改为 `name: shyfrp-artifacts`（`path` 保持 `'**/build/libs/*.jar'`）。

- [ ] **Step 6: 调整 `release.yml`**

资产收集循环：

```bash
          for jar in fabric/build/libs/*.jar forge/build/libs/*.jar neoforge/build/libs/*.jar; do
```

改为：

```bash
          for jar in fabric/build/libs/*.jar; do
```

- [ ] **Step 7: 核对**

```bash
grep -rn "forge\|neoforge" settings.gradle gradle.properties fabric/build.gradle .github/workflows/ -i
```

Expected: 无输出（`gradle.properties` 里 `mod_group_id = io.github.satxm.mcwifipnp` 不含 forge/neoforge，不受影响）。

- [ ] **Step 8: 提交**

```bash
git add -A settings.gradle gradle.properties fabric/build.gradle .github/workflows/ forge neoforge
git commit -m "chore: 精简项目为仅 Fabric 并把产物改名为 shyfrp-<version>.jar

- 删除 forge/、neoforge/ 模块与 publish.yaml（引用已不存在的 quilt 模块）
- settings.gradle / gradle.properties 清理两个平台的仓库与属性
- fabric 产物改为 shyfrp-2.1.4.jar，build/release 工作流只处理 fabric"
```

---

### Task 4: README 清理

**Files:**
- Modify: `README.md`（第 29-31、98-105 行附近）
- Modify: `README.zh-CN.md`（第 29-31、105 行附近）

- [ ] **Step 1: `README.md` 删除平台说明段**

删除 Forge、NeoForge 两个加粗依赖项（第 29、31 行及其空行），保留 Fabric 一行；第 105 行 `Replace \`fabric\` with \`forge\`, \`neoforge\`, or \`quilt\` to build the corresponding artifacts.` 整句删除。

- [ ] **Step 2: `README.zh-CN.md` 同样处理**

删除 Forge、NeoForge 两个依赖项；删除第 105 行 `将\`fabric\`替换为\`forge\`, \`neoforge\`, 或者 \`quilt\`可以构建对应的jar。`

- [ ] **Step 3: 提交**

```bash
git add README.md README.zh-CN.md
git commit -m "docs: README 移除 Forge/NeoForge 说明"
```

---

### Task 5: 构建验证与推送

**Files:** 无（仅验证与推送）

- [ ] **Step 1: 尝试本机完整构建**

精简掉 forge/neoforge 后，本地构建不再依赖连不通的 `maven.minecraftforge.net` / `maven.neoforged.net`，因此有机会跑通：

```bash
cd /d/Project/shyFrp && ./gradlew build --no-daemon
```

Expected: `BUILD SUCCESSFUL`，产出 `fabric/build/libs/shyfrp-2.1.4.jar` 与 `shyfrp-2.1.4-sources.jar`。

若因网络/环境失败（拉不到依赖、JDK 版本不符等）：记录失败原文，改用 Step 2 的兜底核对，并在最终汇报里**如实说明未做完整构建**，以 CI 结果为准。

- [ ] **Step 2: （兜底）用真实 jar 做改动文件的编译核对**

```bash
cd /c/Users/asus/AppData/Local/Temp/shyfrp-verify
/c/DEV/jdk/jdk25/bin/javac -version
```

确认 JDK 25 可用；把 `client.jar`（MC 26.3）、`libs/*.jar`、`cmdapi.jar` 与仓库的 `src/main/java` 一起作为 classpath 编译 FRP 相关文件，确认无「cannot find symbol」类错误。仅用于签名核对，不能替代 Step 1。

- [ ] **Step 3: 推送**

```bash
git push origin 26.3
```

Expected: `26.3 -> 26.3`，随后 GitHub Actions `Build` 工作流触发；观察其 `./gradlew build` 是否通过。

---

## Self-Review

- **Spec 覆盖**：配置模型（Task 1 Step 1）、热重载与迁移（Task 1 Step 1 + Step 3 的 start()）、界面精简与提示行（Task 1 Step 3）、日志全量转发与死代码清理（Task 1 Step 2）、`/shyfrp` 客户端命令（Task 2）、产物改名（Task 3 Step 4）、项目精简与 CI（Task 3）、README（Task 4）、验证（Task 5）——spec 第 3、4、5、6、7、8、9、10、11 节均有对应任务；spec 第 12 节「不在范围内」不设任务。
- **占位符**：无 TBD/TODO；两个重写文件给出完整代码，README 给出精确行号与句子。
- **类型一致性**：`FrpConfig.autoStartEnabled()` / `logToChatEnabled()` / `setAutoStart` / `setLogToChat` / `parsedArgs()` / `getConfigPath()` / `reload()` 在 Task 1 定义，Task 1 Step 3、Task 2 的调用点与此一致；`FrpProcessManager.start()` 无参版本在 Task 1 定义并被 Step 3、`startFrpcIfNeeded` 一致调用；`FrpLogCommand.setLogToChat/currentState/usage` 在 Task 2 定义并被同任务 Step 2 使用。
