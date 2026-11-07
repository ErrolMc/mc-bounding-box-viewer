package com.errolmc.boundingboxviewer.client.overlay;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureStore;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.SpawnArea;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.CuboidGizmo;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the boxes are on, and what they look like. A few times a second it picks the structures in range from
 * {@link StructureStore} and lays out their lines, shading and names as vanilla gizmos (the debug renderer's own: lines
 * cut at the camera, translucent faces, text that faces you); {@link BoxRenderer} hands that same list to the game
 * every frame.
 */
public final class BoxOverlay {
	private static final int REBUILD_INTERVAL_TICKS = 5;

	private static final float OUTER_LINE_WIDTH = 3.0F;
	private static final float PIECE_LINE_WIDTH = 1.5F;
	private static final int OUTER_ALPHA = 0xFF;
	private static final int PIECE_ALPHA = 0xB0;
	private static final int FILL_ALPHA = 0x30;
	/** Boxes sit a little outside the blocks so their lines don't flicker against block faces, the outer box further. */
	private static final double OUTER_INFLATE = 0.02;
	private static final double PIECE_INFLATE = 0.01;
	/** Text scale near by (vanilla's debug text is 0.32), and the distance from which names keep the same size on screen. */
	private static final float LABEL_SCALE = 0.6F;
	private static final double LABEL_NEAR = 12.0;

	private static boolean enabled;
	private static int ticksUntilRebuild;
	private static ResourceKey<Level> builtFor;
	private static List<Gizmo> gizmos = List.of();
	private static List<StructureInfo> shown = List.of();

	private BoxOverlay() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
		clear();
		rebuildSoon();
	}

	/** Rebuild on the next tick, for example after the settings changed. */
	public static void rebuildSoon() {
		ticksUntilRebuild = 0;
	}

	/** What to draw this frame. */
	static List<Gizmo> gizmos() {
		return gizmos;
	}

	/** The structures drawn, nearest first. */
	public static List<StructureInfo> shown() {
		return shown;
	}

	/** Called every client tick. */
	public static void tick(Minecraft minecraft) {
		if (!enabled || minecraft.level == null || minecraft.player == null) {
			clear();
			return;
		}
		// Straight away in a new dimension, so the last one's boxes don't linger.
		if (--ticksUntilRebuild > 0 && minecraft.level.dimension() == builtFor) {
			return;
		}
		ticksUntilRebuild = REBUILD_INTERVAL_TICKS;
		rebuild(minecraft);
	}

	/** Lays out the structures around the player now. */
	public static void rebuild(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			clear();
			return;
		}
		BoxConfig config = BoxConfig.get();
		Vec3 eye = minecraft.player.getEyePosition();
		double rangeSquared = (double) config.range * config.range;

		List<StructureInfo> inRange = new ArrayList<>();
		for (StructureInfo structure : StructureStore.in(minecraft.level.dimension())) {
			if (config.shows(structure.kind()) && structure.bounds().horizontalDistanceSquared(eye.x, eye.z) <= rangeSquared) {
				inRange.add(structure);
			}
		}
		inRange.sort(Comparator.comparingDouble(structure -> structure.bounds().distanceSquared(eye.x, eye.y, eye.z)));

		List<Gizmo> built = new ArrayList<>();
		for (StructureInfo structure : inRange) {
			add(built, structure, config, eye);
		}
		gizmos = List.copyOf(built);
		shown = List.copyOf(inRange);
		builtFor = minecraft.level.dimension();
	}

	private static void add(List<Gizmo> out, StructureInfo structure, BoxConfig config, Vec3 eye) {
		int rgb = structure.color();
		boolean shadeWhole = config.shadeSpawnAreas && structure.spawnArea() == SpawnArea.WHOLE;
		boolean shadePieces = config.shadeSpawnAreas && structure.spawnArea() == SpawnArea.PIECES;

		// A one-piece structure is just its outer box, shaded if its piece is where mobs spawn.
		boolean onePiece = structure.onePiece();
		int outerStroke = argb(OUTER_ALPHA, rgb);
		GizmoStyle outer = shadeWhole || (onePiece && shadePieces)
			? GizmoStyle.strokeAndFill(outerStroke, OUTER_LINE_WIDTH, argb(FILL_ALPHA, rgb))
			: GizmoStyle.stroke(outerStroke, OUTER_LINE_WIDTH);
		cuboid(out, structure.bounds(), OUTER_INFLATE, outer);

		if (!onePiece && (config.showPieces || shadePieces)) {
			GizmoStyle piece;
			if (config.showPieces) {
				int stroke = argb(PIECE_ALPHA, rgb);
				piece = shadePieces ? GizmoStyle.strokeAndFill(stroke, PIECE_LINE_WIDTH, argb(FILL_ALPHA, rgb)) : GizmoStyle.stroke(stroke, PIECE_LINE_WIDTH);
			} else {
				piece = GizmoStyle.fill(argb(FILL_ALPHA, rgb));
			}
			for (Box box : structure.pieces()) {
				cuboid(out, box, PIECE_INFLATE, piece);
			}
		}

		if (config.showLabels) {
			Box bounds = structure.bounds();
			Vec3 at = new Vec3(bounds.centerX(), bounds.top() + 0.75, bounds.centerZ());
			float scale = (float) (LABEL_SCALE * Math.max(1.0, eye.distanceTo(at) / LABEL_NEAR));
			out.add(new TextGizmo(at, Labels.of(structure), TextGizmo.Style.forColorAndCentered(argb(0xFF, rgb)).withScale(scale)));
		}
	}

	private static void cuboid(List<Gizmo> out, Box box, double inflate, GizmoStyle style) {
		AABB aabb = new AABB(box.minX() - inflate, box.minY() - inflate, box.minZ() - inflate,
			box.maxX() + 1 + inflate, box.maxY() + 1 + inflate, box.maxZ() + 1 + inflate);
		out.add(new CuboidGizmo(aabb, style, false));
	}

	private static int argb(int alpha, int rgb) {
		return alpha << 24 | (rgb & 0xFFFFFF);
	}

	private static void clear() {
		gizmos = List.of();
		shown = List.of();
		builtFor = null;
	}
}

