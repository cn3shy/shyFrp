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
