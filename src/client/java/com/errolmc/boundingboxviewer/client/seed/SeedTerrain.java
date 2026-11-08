package com.errolmc.boundingboxviewer.client.seed;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/**
 * The ground as a server has it just before it decorates a chunk: the chunk's terrain step as the game runs it (the
 * noise, the surface, then caves and ravines carved), shaped by the structures near it that reshape the ground
 * (villages, outposts, trail ruins...) as the game shapes it, and its height maps primed. What decorating puts on it is
 * missing: trees, lakes, icebergs, other structures. Chunks are built when first asked for and the last
 * {@value #CACHED} kept. Safe from several threads at once.
 */
final class SeedTerrain {
	/** Chunks kept: a few structures' worth (one can need its neighbours), each about 300 kB. */
	private static final int CACHED = 32;
	/** Chunks whose ground-shaping starts are kept: those a structure's chunks see, 17 x 17 each. */
	private static final int SHAPING_CACHED = 4096;
	/** How far a structure's chunk sees starts, as the game adds references. */
	private static final int REFERENCE_RANGE = 8;
	/** The height maps the game primes after the terrain step; the worldgen ones it keeps as it goes. */
	private static final EnumSet<Heightmap.Types> PRIMED = EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
		Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);

	/** Chunks kept with only their biomes: the ones next to the chunks kept, and then some. */
	private static final int BIOMES_CACHED = 128;

	private final SeedDimension dimension;
	private final StructureManager shapers = new Shapers();
	private final Map<Long, CompletableFuture<ChunkAccess>> chunks = lru(CACHED);
	private final Map<Long, ChunkAccess> biomeChunks = lru(BIOMES_CACHED);
	private final Map<Long, List<StructureStart>> shaping = lru(SHAPING_CACHED);

	SeedTerrain(SeedDimension dimension) {
		this.dimension = dimension;
	}

	/**
	 * The height map's height at the column, as the game's {@code getHeight} gives it while decorating: the first free
	 * block above the ground. From the terrain alone for a generator that doesn't build chunks from noise.
	 */
	int height(Heightmap.Types type, int x, int z) {
		ChunkAccess chunk = chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		return chunk != null ? chunk.getHeight(type, x & 15, z & 15) + 1 : dimension.height(type, x, z);
	}

	/** The block there before decorating. From the terrain's height alone (stone below, air above) for other generators. */
	BlockState block(int x, int y, int z) {
		ChunkAccess chunk = chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		if (chunk != null) {
			return chunk.getBlockState(new BlockPos(x, y, z));
		}
		return y < dimension.height(Heightmap.Types.WORLD_SURFACE_WG, x, z) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	private ChunkAccess chunk(int chunkX, int chunkZ) {
		if (!(dimension.generator() instanceof NoiseBasedChunkGenerator)) {
			return null;
		}
		long key = ChunkPos.pack(chunkX, chunkZ);
		CompletableFuture<ChunkAccess> future;
		boolean mine = false;
		synchronized (chunks) {
			future = chunks.get(key);
			if (future == null) {
				future = new CompletableFuture<>();
				chunks.put(key, future);
				mine = true;
			}
		}
		if (mine) {
			try {
				future.complete(build(chunkX, chunkZ));
			} catch (RuntimeException e) {
				synchronized (chunks) {
					chunks.remove(key, future);
				}
				future.completeExceptionally(e);
				throw e;
			}
		}
		return future.join();
	}

	/**
	 * As a server's biome and terrain steps: a new chunk given its biomes, then filled, surfaced and carved, then its final
	 * height maps primed.
	 */
	private ChunkAccess build(int chunkX, int chunkZ) {
		ChunkAccess chunk = withBiomes(newChunk(chunkX, chunkZ));
		// The surface takes its biomes from the chunk and those next to it, as the server's worldgen region does: the
		// biome step works out a chunk's all at once, which at a border between two can come out unlike the biome source
		// asked about one place. Zoomed with the world's seed, as the region's.
		BiomeManager biomes = new BiomeManager((quartX, quartY, quartZ) -> {
			int x = QuartPos.toSection(quartX);
			int z = QuartPos.toSection(quartZ);
			return (x == chunkX && z == chunkZ ? chunk : biomeChunk(x, z)).getNoiseBiome(quartX, quartY, quartZ);
		}, BiomeManager.obfuscateSeed(dimension.seed()));
		// No worldgen region: the carvers then work out each chunk's biome themselves, as the region would. No possible
		// biomes: the surface rules only use those to skip checks.
		ChunkAccess built = dimension.generator().buildTerrain(chunk, Blender.empty(), dimension.randomState(), shapers, biomes, null, null).join();
		Heightmap.primeHeightmaps(built, PRIMED);
		return built;
	}

	/** A chunk with only its biomes, kept for the chunks next to it. */
	private ChunkAccess biomeChunk(int chunkX, int chunkZ) {
		long key = ChunkPos.pack(chunkX, chunkZ);
		synchronized (biomeChunks) {
			ChunkAccess known = biomeChunks.get(key);
			if (known != null) {
				return known;
			}
		}
		ChunkAccess chunk = withBiomes(newChunk(chunkX, chunkZ));
		synchronized (biomeChunks) {
			ChunkAccess known = biomeChunks.putIfAbsent(key, chunk);
			return known != null ? known : chunk;
		}
	}

	private ProtoChunk newChunk(int chunkX, int chunkZ) {
		return new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, dimension.heightAccessor(), dimension.containers(), null);
	}

	/** The chunk's biomes as the server's biome step fills them, and the chunk marked as that far, as the server does. */
	private ChunkAccess withBiomes(ProtoChunk chunk) {
		dimension.generator().createBiomes(dimension.randomState(), Blender.empty(), shapers, chunk).join();
		chunk.setPersistedStatus(ChunkStatus.BIOMES);
		return chunk;
	}

	/** The starts in the chunk of the structures that reshape the ground around them, as seed mode plans them. */
	private List<StructureStart> shaping(int chunkX, int chunkZ) {
		long key = ChunkPos.pack(chunkX, chunkZ);
		synchronized (shaping) {
			List<StructureStart> known = shaping.get(key);
			if (known != null) {
				return known;
			}
		}
		List<StructureStart> starts = dimension.plan(chunkX, chunkZ, SeedTerrain::shapesGround).stream().map(SeedDimension.Planned::start).toList();
		synchronized (shaping) {
			shaping.put(key, starts);
		}
		return starts;
	}

	private static boolean shapesGround(Structure structure) {
		return structure.terrainAdaptation() != TerrainAdjustment.NONE;
	}

	/**
	 * What the terrain step asks a structure manager: the starts that reach the chunk, as its references name them (the
	 * starts within 8 chunks whose box overlaps it). It asks for nothing else.
	 */
	private final class Shapers extends StructureManager {
		Shapers() {
			super(null, null, null);
		}

		@Override
		public List<StructureStart> startsForStructure(int chunkX, int chunkZ, Predicate<Structure> matcher) {
			int minX = SectionPos.sectionToBlockCoord(chunkX);
			int minZ = SectionPos.sectionToBlockCoord(chunkZ);
			List<StructureStart> found = new ArrayList<>();
			for (int dx = -REFERENCE_RANGE; dx <= REFERENCE_RANGE; dx++) {
				for (int dz = -REFERENCE_RANGE; dz <= REFERENCE_RANGE; dz++) {
					for (StructureStart start : shaping(chunkX + dx, chunkZ + dz)) {
						if (matcher.test(start.getStructure()) && start.getBoundingBox().intersects(minX, minZ, minX + 15, minZ + 15)) {
							found.add(start);
						}
					}
				}
			}
			return found;
		}

		@Override
		public List<StructureStart> startsForStructure(int chunkX, int chunkZ, Structure structure) {
			return startsForStructure(chunkX, chunkZ, structure::equals);
		}
	}

	private static <V> Map<Long, V> lru(int size) {
		return new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
				return size() > size;
			}
		};
	}
}
