package com.errolmc.boundingboxviewer.client.world;

import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.SpawnArea;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Turns the game's structures into {@link StructureInfo}s, the same way whether they come from a server or a seed. */
public final class StructureInfos {
	private StructureInfos() {
	}

	/**
	 * @param pieces the boxes of the structure's pieces, where they are now
	 */
	public static StructureInfo of(String id, Structure structure, long startChunk, List<BoundingBox> pieces) {
		List<Box> boxes = new ArrayList<>(pieces.size());
		for (BoundingBox piece : pieces) {
			boxes.add(box(piece));
		}
		return new StructureInfo(id, StructureKind.of(id), startChunk, box(bounds(structure, pieces)), boxes, spawnArea(structure));
	}

	/** The boxes of the start's pieces where they are now, each as {@link #pieceBox} has it. */
	public static List<BoundingBox> pieces(StructureStart start, StructureTemplateManager templates) {
		List<BoundingBox> pieces = new ArrayList<>(start.getPieces().size());
		for (StructurePiece piece : start.getPieces()) {
			pieces.add(pieceBox(piece, templates));
		}
		return pieces;
	}

	/**
	 * A piece's box as the game has it once the world is loaded again. For jigsaw pieces (villages, outposts, bastions,
	 * ancient cities, trail ruins, trial chambers) that's the template's box: some are planned with theirs stretched up
	 * to make room for the pieces on top ({@code use_expansion_hack}), which the game doesn't save.
	 */
	public static BoundingBox pieceBox(StructurePiece piece, StructureTemplateManager templates) {
		if (piece instanceof PoolElementStructurePiece pool) {
			return pool.getElement().getBoundingBox(templates, pool.getPosition(), pool.getRotation());
		}
		return piece.getBoundingBox();
	}

	/**
	 * The structure's box as the game works it out: around all its pieces, 12 blocks bigger for structures that reshape
	 * the ground around them (villages, outposts, strongholds, ancient cities, trail ruins, trial chambers). Not
	 * {@code StructureStart.getBoundingBox()}: that is worked out once, before pieces settle onto the ground, and kept
	 * until the world is loaded again, so a swamp hut's would be at the height it was planned at.
	 */
	public static BoundingBox bounds(Structure structure, List<BoundingBox> pieces) {
		return structure.adjustBoundingBox(BoundingBox.encapsulatingBoxes(pieces).orElseThrow());
	}

	public static Box box(BoundingBox box) {
		return new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
	}

	/** Where the structure's own mobs spawn: overrides without mobs only stop other spawns, so they don't count. */
	public static SpawnArea spawnArea(Structure structure) {
		SpawnArea area = SpawnArea.NONE;
		for (StructureSpawnOverride override : structure.spawnOverrides().values()) {
			if (override.spawns().isEmpty()) {
				continue;
			}
			if (override.boundingBox() == StructureSpawnOverride.BoundingBoxType.STRUCTURE) {
				return SpawnArea.WHOLE;
			}
			area = SpawnArea.PIECES;
		}
		return area;
	}
}
