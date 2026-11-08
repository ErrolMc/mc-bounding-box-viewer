package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureInfos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * One dimension of a world worked out from its seed: the same chunk generator, noise and structure placement a server
 * with that seed has, so the structures it plans in a chunk are the server's. Safe to use from several threads at once,
 * as the game's own world generation is.
 */
public final class SeedDimension {
	/** A structure start as the game plans it in its chunk, before any piece settles onto the ground. */
	public record Planned(String id, Holder<Structure> structure, StructureStart start) {
		public long chunk() {
			return start.getChunkPos().pack();
		}
	}

	private final long seed;
	private final ResourceKey<Level> level;
	private final RegistryAccess registries;
	private final StructureTemplateManager templates;
	private final Registry<Structure> structures;
	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final ChunkGeneratorStructureState structureState;
	private final LevelHeightAccessor heightAccessor;
	private final PalettedContainerFactory containers;
	/** Each structure's place among those placed in the same decoration step, which seeds its pieces' random. */
	private final Map<Structure, Integer> decorationIndex = new HashMap<>();
	private final SeedTerrain terrain;

	SeedDimension(VanillaWorldgen worldgen, long seed, ResourceKey<Level> level, LevelStem stem) {
		this.seed = seed;
		this.level = level;
		this.registries = worldgen.registries();
		this.templates = worldgen.templates();
		this.structures = registries.lookupOrThrow(Registries.STRUCTURE);
		this.generator = stem.generator();
		// As ChunkMap sets up a level's generator.
		this.randomState = generator instanceof NoiseBasedChunkGenerator noise
			? RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, noise.generatorSettings().value())
			: RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, false, Blocks.STONE.defaultBlockState(), 63, NoiseRouterData.none());
		this.structureState = generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
		DimensionType type = stem.type().value();
		this.heightAccessor = LevelHeightAccessor.create(type.minY(), type.height());
		this.containers = PalettedContainerFactory.create(registries);
		// As ChunkGenerator.applyBiomeDecoration numbers them: in registry order within each step.
		Map<Integer, Integer> nextIndex = new HashMap<>();
		structures.stream().forEach(structure -> decorationIndex.put(structure, nextIndex.merge(structure.step().ordinal(), 1, Integer::sum) - 1));
		this.terrain = new SeedTerrain(this);
	}

	public long seed() {
		return seed;
	}

	public ResourceKey<Level> level() {
		return level;
	}

	public ChunkGenerator generator() {
		return generator;
	}

	public RandomState randomState() {
		return randomState;
	}

	public ChunkGeneratorStructureState structureState() {
		return structureState;
	}

	public LevelHeightAccessor heightAccessor() {
		return heightAccessor;
	}

	public RegistryAccess registries() {
		return registries;
	}

	StructureTemplateManager templates() {
		return templates;
	}

	/** For chunks of this dimension's blocks. */
	PalettedContainerFactory containers() {
		return containers;
	}

	/** The structure's id, for example {@code minecraft:village_plains}. */
	public String id(Structure structure) {
		Identifier key = structures.getKey(structure);
		return key != null ? key.toString() : "";
	}

	/** The ground height at a column as a height map of this type would give it, from the terrain's noise alone. */
	public int height(Heightmap.Types type, int x, int z) {
		return generator.getBaseHeight(x, z, type, heightAccessor, randomState);
	}

	/**
	 * The ground height at a column as the game reads it while decorating the chunk (the first free block above), on
	 * the chunk's terrain as a server builds it: caves and ravines carved, villages and the like shaping it; nothing
	 * decorating adds.
	 */
	public int ground(Heightmap.Types type, int x, int z) {
		return terrain.height(type, x, z);
	}

	/** The block there on the chunk's terrain as a server builds it, before decorating (see {@link #ground}). */
	public BlockState groundBlock(int x, int y, int z) {
		return terrain.block(x, y, z);
	}

	/**
	 * The structures the game starts in this chunk, as {@code ChunkGenerator.createStructures} picks them: for every
	 * structure set placed in the chunk, its only structure or, for sets of several (villages, ruined portals), one picked
	 * by weight with a random seeded from the chunk, the next tried when one can't go there.
	 */
	public List<Planned> plan(int chunkX, int chunkZ) {
		return plan(chunkX, chunkZ, structure -> true);
	}

	/** As {@link #plan(int, int)}, only the structure sets with a structure that passes {@code only}, and only those starts. */
	List<Planned> plan(int chunkX, int chunkZ, Predicate<Structure> only) {
		ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
		Climate.Sampler sampler = null;
		List<Planned> planned = new ArrayList<>(1);
		for (Holder<StructureSet> set : structureState.possibleStructureSets()) {
			StructurePlacement placement = set.value().placement();
			if (set.value().structures().stream().noneMatch(entry -> only.test(entry.structure().value()))
				|| !placement.isStructureChunk(structureState, chunkX, chunkZ)) {
				continue;
			}
			if (sampler == null) {
				// Caches as it samples, so one per chunk and thread, as the game uses it.
				sampler = randomState.createClimateSampler(SamplerContext.builder().enableCaches().build());
			}
			List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
			if (entries.size() == 1) {
				Planned start = tryStart(entries.getFirst(), chunk, sampler);
				if (start != null && only.test(start.structure().value())) {
					planned.add(start);
				}
				continue;
			}
			List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(entries);
			WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
			random.setLargeFeatureSeed(structureState.getLevelSeed(), chunkX, chunkZ);
			int total = 0;
			for (StructureSet.StructureSelectionEntry option : options) {
				total += option.weight();
			}
			while (!options.isEmpty()) {
				int choice = random.nextInt(total);
				int index = 0;
				for (StructureSet.StructureSelectionEntry option : options) {
					choice -= option.weight();
					if (choice < 0) {
						break;
					}
					index++;
				}
				StructureSet.StructureSelectionEntry selected = options.get(index);
				Planned start = tryStart(selected, chunk, sampler);
				if (start != null) {
					if (only.test(start.structure().value())) {
						planned.add(start);
					}
					break;
				}
				options.remove(index);
				total -= selected.weight();
			}
		}
		return planned;
	}

	private Planned tryStart(StructureSet.StructureSelectionEntry entry, ChunkPos chunk, Climate.Sampler sampler) {
		Holder<Structure> holder = entry.structure();
		Structure structure = holder.value();
		StructureStart start = structure.generate(holder, level, registries, generator, generator.getBiomeSource(), sampler, randomState, templates,
			structureState.getLevelSeed(), chunk, 0, heightAccessor, structure.biomes()::contains);
		return start.isValid() ? new Planned(id(structure), holder, start) : null;
	}

	/** The start as seed mode shows it: pieces that settle onto the ground moved to where the terrain is. */
	public StructureInfo info(Planned planned) {
		return StructureInfos.of(planned.id(), planned.structure().value(), planned.chunk(), SeedSettling.settle(this, planned));
	}

	/** The start as planned, nothing settled: what a server has before the chunks around it are decorated. */
	public StructureInfo plannedInfo(Planned planned) {
		return StructureInfos.of(planned.id(), planned.structure().value(), planned.chunk(), StructureInfos.pieces(planned.start(), templates));
	}

	/** Every structure starting in the chunk, as seed mode shows them. */
	public List<StructureInfo> structuresStartingIn(int chunkX, int chunkZ) {
		List<Planned> planned = plan(chunkX, chunkZ);
		List<StructureInfo> infos = new ArrayList<>(planned.size());
		for (Planned start : planned) {
			infos.add(info(start));
		}
		return infos;
	}

	/**
	 * The random a structure's pieces get when the chunk is decorated: seeded from the world seed, the chunk's corner,
	 * the structure's place in its decoration step and the step.
	 */
	WorldgenRandom decorationRandom(Structure structure, int chunkX, int chunkZ) {
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		long decorationSeed = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
		random.setFeatureSeed(decorationSeed, decorationIndex.getOrDefault(structure, 0), structure.step().ordinal());
		return random;
	}
}
