package com.errolmc.boundingboxviewer.client.input;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** B toggles the boxes; the see-through key (unbound by default) switches drawing through blocks. Both rebindable under Controls. */
public final class BoxKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bounding-box-viewer", "main"));

	public static final KeyMapping TOGGLE = new KeyMapping("key.bounding-box-viewer.toggle", InputConstants.KEY_B, CATEGORY);
	public static final KeyMapping SEE_THROUGH = new KeyMapping("key.bounding-box-viewer.see_through", InputConstants.UNKNOWN.getValue(), CATEGORY);

	private BoxKeys() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		KeyMappingHelper.registerKeyMapping(SEE_THROUGH);
	}

	/** Called every client tick. */
	public static void tick(Minecraft minecraft) {
		while (TOGGLE.consumeClick()) {
			toggle(minecraft);
		}
		while (SEE_THROUGH.consumeClick()) {
			BoxConfig config = BoxConfig.get();
			config.seeThrough = !config.seeThrough;
			BoxConfig.save();
			if (!BoxOverlay.isEnabled()) {
				toggle(minecraft);
			} else if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.translatable(config.seeThrough
					? "bounding-box-viewer.message.see_through_on" : "bounding-box-viewer.message.see_through_off"));
			}
		}
	}

	private static void toggle(Minecraft minecraft) {
		if (BoxOverlay.isEnabled()) {
			BoxOverlay.setEnabled(false);
			say(minecraft, Component.translatable("bounding-box-viewer.message.off"));
			return;
		}
		// The client only knows structures through the singleplayer server; a multiplayer server never sends them.
		if (!minecraft.hasSingleplayerServer()) {
			say(minecraft, Component.translatable("bounding-box-viewer.message.singleplayer_only"));
			return;
		}
		BoxOverlay.setEnabled(true);
		BoxOverlay.rebuild(minecraft);
		int count = BoxOverlay.shown().size();
		say(minecraft, count == 0
			? Component.translatable("bounding-box-viewer.message.on_none", BoxConfig.get().range)
			: Component.translatable(count == 1 ? "bounding-box-viewer.message.on_one" : "bounding-box-viewer.message.on", count, BoxConfig.get().range));
	}

	private static void say(Minecraft minecraft, Component message) {
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}

	/** The toggle key's current binding, for example "B". */
	public static Component toggleKeyName() {
		return TOGGLE.getTranslatedKeyMessage();
	}
}

