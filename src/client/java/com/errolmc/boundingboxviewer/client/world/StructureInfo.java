package com.errolmc.boundingboxviewer.client.world;

import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.SpawnArea;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import java.util.List;

/**
 * One structure as the server generated it: the box around all of it and the box of each piece (a fortress corridor,
 * a village house or road, a mineshaft tunnel). Immutable, so the client thread can read it while the server thread
 * adds more.
 *
 * @param id the structure's id, for example {@code minecraft:swamp_hut}
 * @param startChunk the chunk the structure starts in ({@code ChunkPos.pack()}), which with the id names it
 */
public record StructureInfo(String id, StructureKind kind, long startChunk, Box bounds, List<Box> pieces, SpawnArea spawnArea) {
	public StructureInfo {
		pieces = List.copyOf(pieces);
	}

	/** RGB, no alpha. */
	public int color() {
		return kind.color(id);
	}

	/** True when the structure is a single piece that fills its whole box (a swamp hut, a desert pyramid). */
	public boolean onePiece() {
		return pieces.size() == 1 && pieces.getFirst().equals(bounds);
	}
}
