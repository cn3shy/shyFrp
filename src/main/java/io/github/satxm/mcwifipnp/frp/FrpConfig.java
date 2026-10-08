package io.github.satxm.mcwifipnp.frp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import io.github.satxm.mcwifipnp.MCWiFiPnPUnit;
import net.minecraft.client.Minecraft;

/**
 * FRP（内网穿透）全局配置，所有世界共用。
 * 存储位置：&lt;游戏目录&gt;/config/mcwifipnp-frp.json
 * <p>
 * 仅客户端使用（frpc 是客户端功能）。
 */
public class FrpConfig {
	/** frpc 可执行文件路径。 */
	@SerializedName("frpc-path")
	public String frpcPath = "C:\\Users\\sspan\\Downloads\\frpc_windows_amd64.exe";

	/** 访问密钥（樱花frp）。 */
	@SerializedName("access-token")
	public String token = "";

	/** 隧道（映射）ID（樱花frp）。 */
	@SerializedName("tunnel-id")
	public String tunnelId = "";

	/** 发布局域网后是否自动启动 frpc。 */
	@SerializedName("auto-start")
	public boolean autoStart = true;

	/** 是否把 frpc 关键输出推送到游戏聊天栏。 */
	@SerializedName("push-chat-events")
	public boolean pushChatEvents = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static FrpConfig instance;

	private FrpConfig() {
	}

	/** 获取全局配置实例（首次访问时从磁盘读取）。 */
	public static synchronized FrpConfig get() {
		if (instance == null) {
			instance = read();
		}
		return instance;
	}

	public static Path getConfigPath() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mcwifipnp-frp.json");
	}

	private static FrpConfig read() {
		Path path = getConfigPath();
		if (Files.exists(path)) {
			try {
				FrpConfig cfg = GSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), FrpConfig.class);
				if (cfg != null) {
					return cfg;
				}
			} catch (IOException | JsonParseException e) {
				MCWiFiPnPUnit.LOGGER.warn("Unable to read FRP config, using defaults", e);
			}
		}
		return new FrpConfig();
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
}
