package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.client.world.StructureInfos;
import com.errolmc.boundingboxviewer.structure.Settling;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import net.minecraft.world.level.levelgen.structure.structures.JungleTemplePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinPieces;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckPieces;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Where the pieces that settle onto the ground end up, from the seed's terrain. A server moves them when it decorates
 * their chunk, reading the ground as it is then: these read it as the server's terrain step leaves it (caves and
 * ravines carved: {@link SeedDimension#ground}), before lakes, trees, icebergs or neighbouring structures, so their
 * height is a close estimate (x and z never change). Where the game picks a random offset, it is the one the
 * structure's start chunk gives, as if that chunk were decorated first.
 */
public final class SeedSettling {
	/** The planned height of igloos' templates, which the game moves to the ground from. */
	private static final int IGLOO_HEIGHT = IglooPieces.GENERATION_HEIGHT;
	/** Igloo templates' offsets from the top piece, as IglooPieces has them. */
	private static final Map<String, Vec3i> IGLOO_OFFSETS = Map.of(
		"igloo/top", Vec3i.ZERO,
		"igloo/middle", new Vec3i(2, -3, 4),
		"igloo/bottom", new Vec3i(0, -3, -2));

	private SeedSettling() {
	}

	/** The start's piece boxes, with pieces that settle moved. */
	static List<BoundingBox> settle(SeedDimension dimension, SeedDimension.Planned planned) {
		List<StructurePiece> pieces = planned.start().getPieces();
		List<BoundingBox> boxes = new ArrayList<>(pieces.size());
		ChunkPos chunk = planned.start().getChunkPos();
		Structure structure = planned.structure().value();
		for (StructurePiece piece : pieces) {
			boxes.add(settled(dimension, structure, chunk, piece));
		}
		return boxes;
	}

	/** One piece's box once settled; pieces that stay where they're planned keep theirs. */
	static BoundingBox settled(SeedDimension dimension, Structure structure, ChunkPos chunk, StructurePiece piece) {
		BoundingBox box = StructureInfos.pieceBox(piece, dimension.templates());
		if (piece instanceof SwampHutPiece || piece instanceof JungleTemplePiece) {
			// The average ground under the part of the piece in the first chunk decorated, which for these is all of it:
			// they're planned from their chunk's corner and smaller than a chunk.
			int x1 = Math.max(box.minX(), chunk.getMinBlockX());
			int z1 = Math.max(box.minZ(), chunk.getMinBlockZ());
			int x2 = Math.min(box.maxX(), chunk.getMaxBlockX());
			int z2 = Math.min(box.maxZ(), chunk.getMaxBlockZ());
			int ground = Settling.average(x1, z1, x2, z2, heights(dimension, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
			return box.moved(0, ground - box.minY(), 0);
		}
		if (piece instanceof DesertPyramidPiece) {
			int offset = -random(dimension, structure, chunk).nextInt(3);
			int ground = Settling.lowest(box.minX(), box.minZ(), box.maxX(), box.maxZ(), heights(dimension, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
			return box.moved(0, ground - box.minY() + offset, 0);
		}
		if (piece instanceof IglooPieces.IglooPiece igloo) {
			Vec3i offset = iglooOffset(dimension.templates(), igloo);
			if (offset == null) {
				return box;
			}
			BlockPos entrance = igloo.templatePosition()
				.offset(StructureTemplate.calculateRelativePosition(igloo.placeSettings(), new BlockPos(3 - offset.getX(), 0, -offset.getZ())));
			int ground = dimension.ground(Heightmap.Types.WORLD_SURFACE_WG, entrance.getX(), entrance.getZ());
			return box.moved(0, ground - IGLOO_HEIGHT - 1, 0);
		}
		if (piece instanceof ShipwreckPieces.ShipwreckPiece ship) {
			return box.moved(0, shipwreckHeight(dimension, structure, chunk, ship) - ship.templatePosition().getY(), 0);
		}
		if (piece instanceof OceanRuinPieces.OceanRuinPiece ruin) {
			BlockPos origin = ruin.templatePosition();
			Vec3i size = ruin.template().getSize();
			BlockPos corner = StructureTemplate.transform(new BlockPos(size.getX() - 1, 0, size.getZ() - 1), Mirror.NONE, ruin.getRotation(), BlockPos.ZERO)
				.offset(origin);
			int y = Settling.oceanRuin(origin.getX(), origin.getZ(), corner.getX(), corner.getZ(),
				dimension.ground(Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ()), (x, z, top) -> floorBelow(dimension, x, z, top));
			return box.moved(0, y - origin.getY(), 0);
		}
		if (piece instanceof BuriedTreasurePieces.BuriedTreasurePiece) {
			BlockPos chest = buriedTreasure(dimension, box.minX(), box.minZ());
			return chest != null ? new BoundingBox(chest) : box;
		}
		return box;
	}

	/**
	 * Where BuriedTreasurePiece digs its chest in: down from the sea floor to the first block on sandstone or stone
	 * (andesite, granite, diorite too). Null if there's none, when the game places nothing.
	 */
	private static BlockPos buriedTreasure(SeedDimension dimension, int x, int z) {
		int minY = dimension.heightAccessor().getMinY();
		for (int y = dimension.ground(Heightmap.Types.OCEAN_FLOOR_WG, x, z); y > minY; y--) {
			BlockState below = dimension.groundBlock(x, y - 1, z);
			if (below.is(Blocks.SANDSTONE) || below.is(Blocks.STONE) || below.is(Blocks.ANDESITE) || below.is(Blocks.GRANITE) || below.is(Blocks.DIORITE)) {
				return new BlockPos(x, y, z);
			}
		}
		return null;
	}

	/** As OceanRuinPiece looks down a column: through air, water and ice to the floor, no lower than one above the bottom. */
	private static int floorBelow(SeedDimension dimension, int x, int z, int top) {
		int bottom = dimension.heightAccessor().getMinY() + 1;
		int y = top;
		BlockState state = dimension.groundBlock(x, y, z);
		while ((state.isAir() || state.getFluidState().is(FluidTags.WATER) || state.is(BlockTags.ICE)) && y > bottom) {
			state = dimension.groundBlock(x, --y, z);
		}
		return y;
	}

	/** True for the pieces whose height {@link #settled} estimates. */
	public static boolean settles(StructurePiece piece) {
		return piece instanceof SwampHutPiece || piece instanceof JungleTemplePiece || piece instanceof DesertPyramidPiece
			|| piece instanceof IglooPieces.IglooPiece || piece instanceof ShipwreckPieces.ShipwreckPiece
			|| piece instanceof OceanRuinPieces.OceanRuinPiece || piece instanceof BuriedTreasurePieces.BuriedTreasurePiece;
	}

	/** As ShipwreckPiece works it out: the mean ground under the template's unrotated footprint, or for beached ones the lowest less half its height. */
	private static int shipwreckHeight(SeedDimension dimension, Structure structure, ChunkPos chunk, ShipwreckPieces.ShipwreckPiece ship) {
		BlockPos origin = ship.templatePosition();
		if (ship.isTooBigToFitInWorldGenRegion()) {
			return origin.getY();
		}
		boolean beached = structure instanceof ShipwreckStructure shipwreck && shipwreck.isBeached;
		Heightmap.Types type = beached ? Heightmap.Types.WORLD_SURFACE_WG : Heightmap.Types.OCEAN_FLOOR_WG;
		Vec3i size = ship.template().getSize();
		if (size.getX() * size.getZ() == 0) {
			return dimension.ground(type, origin.getX(), origin.getZ());
		}
		int x2 = origin.getX() + size.getX() - 1;
		int z2 = origin.getZ() + size.getZ() - 1;
		Settling.Heights heights = heights(dimension, type);
		if (!beached) {
			return Settling.average(origin.getX(), origin.getZ(), x2, z2, heights);
		}
		int lowest = Settling.lowest(origin.getX(), origin.getZ(), x2, z2, heights);
		return lowest - size.getY() / 2 - random(dimension, structure, chunk).nextInt(3);
	}

	private static Vec3i iglooOffset(StructureTemplateManager templates, TemplateStructurePiece piece) {
		for (Map.Entry<String, Vec3i> entry : IGLOO_OFFSETS.entrySet()) {
			if (piece.template() == templates.getOrCreate(Identifier.withDefaultNamespace(entry.getKey()))) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static WorldgenRandom random(SeedDimension dimension, Structure structure, ChunkPos chunk) {
		return dimension.decorationRandom(structure, chunk.x(), chunk.z());
	}

	private static Settling.Heights heights(SeedDimension dimension, Heightmap.Types type) {
		return (x, z) -> dimension.ground(type, x, z);
	}
}
