package com.errolmc.boundingboxviewer.client.overlay;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.Gizmos;

/**
 * Adds the overlay's gizmos to the game's own gizmo list every frame, just before the game draws that list: depth
 * tested like the world, or always on top (vanilla's pass for debug shapes seen through walls) when see-through is on.
 *
 * <p>Through the list rather than straight to the submit collector, because the game only sets up its always-on-top
 * pass when its own list has always-on-top gizmos in it; submitted on their own they're dropped.
 */
public final class BoxRenderer {
	private BoxRenderer() {
	}

	public static void register() {
		LevelRenderEvents.BEFORE_GIZMOS.register(BoxRenderer::addGizmos);
	}

	private static void addGizmos(LevelRenderContext context) {
		List<Gizmo> gizmos = BoxOverlay.gizmos();
		if (gizmos.isEmpty()) {
			return;
		}
		boolean onTop = BoxConfig.get().seeThrough;
		try (Gizmos.TemporaryCollection ignored = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
			for (Gizmo gizmo : gizmos) {
				GizmoProperties properties = Gizmos.addGizmo(gizmo);
				if (onTop) {
					properties.setAlwaysOnTop();
				}
			}
		}
	}
}
