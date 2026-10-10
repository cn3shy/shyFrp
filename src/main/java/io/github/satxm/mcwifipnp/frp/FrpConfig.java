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
