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

		// 客户端命令：单机/局域网存档下无需开作弊或 OP
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
