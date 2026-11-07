package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.structure.SeedText;
import com.errolmc.boundingboxviewer.structure.WorldType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Arrays;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * {@code /boxes seed} shows, sets ({@code /boxes seed <seed> [normal|large_biomes|amplified]}) or forgets
 * ({@code /boxes seed clear}) the seed of the server you're on. And {@code /seed}'s reply, for operators, is picked up
 * by itself.
 */
public final class SeedCommands {
	/** The key of {@code /seed}'s reply, whose one argument is the seed in brackets. */
	static final String SEED_REPLY = "commands.seed.success";

	private SeedCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("boxes")
			.then(ClientCommands.literal("seed")
				.executes(context -> show(context.getSource()))
				.then(ClientCommands.literal("clear")
					.executes(context -> clear(context.getSource())))
				.then(ClientCommands.argument("seed", StringArgumentType.string())
					.executes(context -> set(context.getSource(), StringArgumentType.getString(context, "seed"), null))
					.then(ClientCommands.argument("type", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(WorldType.values()).map(WorldType::key), builder))
						.executes(context -> set(context.getSource(), StringArgumentType.getString(context, "seed"),
							StringArgumentType.getString(context, "type"))))))));
		ClientReceiveMessageEvents.GAME.register(SeedCommands::onGameMessage);
	}

	private static int show(FabricClientCommandSource source) {
		if (!SeedMode.isActive()) {
			source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_singleplayer"));
			return 0;
		}
		BoxConfig.SavedSeed saved = SeedMode.current();
		if (saved == null) {
			source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_none"));
			return 0;
		}
		source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_show", Long.toString(saved.seed), saved.type().key()));
		if (SeedMode.status() == SeedMode.Status.FAILED) {
			source.sendError(Component.translatable("bounding-box-viewer.message.seed_failed"));
		}
		return 1;
	}

	private static int clear(FabricClientCommandSource source) {
		if (!SeedMode.clearSeed()) {
			source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_singleplayer"));
			return 0;
		}
		source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_cleared"));
		return 1;
	}

	private static int set(FabricClientCommandSource source, String text, String typeKey) {
		WorldType type = typeKey == null ? WorldType.NORMAL : WorldType.byKey(typeKey);
		if (type == null) {
			source.sendError(Component.translatable("bounding-box-viewer.message.seed_unknown_type", typeKey));
			return 0;
		}
		OptionalLong seed = SeedText.parse(text);
		if (seed.isEmpty()) {
			source.sendError(Component.translatable("bounding-box-viewer.message.seed_none"));
			return 0;
		}
		if (!SeedMode.setSeed(seed.getAsLong(), type)) {
			source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_singleplayer"));
			return 0;
		}
		source.sendFeedback(Component.translatable("bounding-box-viewer.message.seed_saved", Long.toString(seed.getAsLong()), type.key()));
		return 1;
	}

	/** {@code /seed}'s reply on a server: the seed, saved for it, keeping the world type already saved. */
	private static void onGameMessage(Component message, boolean overlay) {
		if (overlay || !SeedMode.isActive()) {
			return;
		}
		OptionalLong seed = seedIn(message);
		if (seed.isEmpty()) {
			return;
		}
		BoxConfig.SavedSeed was = SeedMode.current();
		WorldType type = was != null ? was.type() : WorldType.NORMAL;
		if (was != null && was.seed == seed.getAsLong()) {
			return;
		}
		SeedMode.setSeed(seed.getAsLong(), type);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.translatable("bounding-box-viewer.message.seed_saved", Long.toString(seed.getAsLong()), type.key()));
		}
	}

	/** The seed in {@code /seed}'s reply, or empty for any other message. */
	static OptionalLong seedIn(Component message) {
		if (!(message.getContents() instanceof TranslatableContents contents) || !contents.getKey().equals(SEED_REPLY) || contents.getArgs().length != 1) {
			return OptionalLong.empty();
		}
		Object arg = contents.getArgs()[0];
		return SeedText.fromSeedCommand(arg instanceof Component component ? component.getString() : String.valueOf(arg));
	}
}
