package com.errolmc.boundingboxviewer.client;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.input.BoxKeys;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.overlay.BoxRenderer;
import com.errolmc.boundingboxviewer.client.world.StructureCollector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BoundingBoxViewerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BoxConfig.load();
		BoxKeys.register();
		BoxRenderer.register();
		// Server-side events: in singleplayer the integrated server runs in this game, and it is where structures are.
		StructureCollector.register();
		ClientTickEvents.END_CLIENT_TICK.register(BoxKeys::tick);
		ClientTickEvents.END_CLIENT_TICK.register(BoxOverlay::tick);
	}
}

