package com.errolmc.boundingboxviewer.gametest;

import static com.errolmc.boundingboxviewer.gametest.TestWorlds.check;

import com.errolmc.boundingboxviewer.client.seed.SeedDimension;
import com.errolmc.boundingboxviewer.client.seed.SeedSettling;
import com.errolmc.boundingboxviewer.client.seed.SeedWorld;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.WorldType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed mode against the server, world by world: six worlds (four seeds of the default type, one with large biomes, one
 * amplified), each dimension. Seed mode works out the world from the seed alone, as it does on a server, and each part
 * must be the server's:
 *
 * <ul>
 * <li>the structure sets the dimension can place, its origin, and the stronghold rings;</li>
 * <li>the terrain's height (three height maps) and the biomes, on a grid in four areas, from near spawn to a million
 * blocks out;</li>
 * <li>whether each structure set is placed in each chunk of those areas (about 18,000 chunks a dimension);</li>
 * <li>the structures starting in a few hundred of those chunks, picked per set, and the nearest of every structure the
 * areas didn't have: the same structures, pieces and boxes as planned;</li>
 * <li>for the structures that settle onto the ground, a few of each decorated on the server: the same pieces, the
 * heights measured (logged) and within limits.</li>
 * </ul>
 *
 * The areas are far from spawn, so nothing in them is decorated until the settling part does it.
 */
public class SeedPlanningClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer-gametest");
	private static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

	private record World(long seed, WorldType type) {
		@Override
		public String toString() {
			return "seed " + seed + " (" + type.key() + ")";
		}
	}

	private static final List<World> WORLDS = List.of(
		new World(607, WorldType.NORMAL),
		new World(8478637045098429971L, WorldType.NORMAL),
		new World(1, WorldType.NORMAL),
		new World(-8372611429371922173L, WorldType.NORMAL),
		new World(20261108, WorldType.NORMAL),
		new World(424242, WorldType.LARGE_BIOMES),
		new World(31337, WorldType.AMPLIFIED));

	/** A square of chunks: its middle and how far it reaches each way. */
	private record Area(int x, int z, int radius) {
		double distanceSquared(long chunk) {
			double dx = ChunkPos.getX(chunk) - x;
			double dz = ChunkPos.getZ(chunk) - z;
			return dx * dx + dz * dz;
		}
	}

	private static final List<Area> AREAS = List.of(
		new Area(150, -110, 40),
		new Area(-900, 650, 40),
		new Area(2400, 1700, 24),
		new Area(-62500, 47000, 24));
	/**
	 * Where locate starts from for the structures the areas don't have (or too few of, for those that settle): far from
	 * spawn, then the areas' middles. Not spawn: the chunks there are decorated, so what settles has. Locate only looks so
	 * far (100 chunks for mineshafts), so a rare biome's structure may be missing from one world, but every structure must
	 * be compared in some world.
	 */
	private static final List<BlockPos> LOCATE_FROM = List.of(new BlockPos(12000, 64, -8000), new BlockPos(2408, 64, -1752),
		new BlockPos(-14392, 64, 10408), new BlockPos(38408, 64, 27208), new BlockPos(-999992, 64, 752008));
	/** Chunks to compare the starts of, per structure set and area: the nearest the area's middle the set is placed in. */
	private static final int PER_SET = 12;
	/** Starts compared per batch, one server task each. */
	private static final int BATCH = 48;
	/** Structures that settle decorated and compared per structure and dimension. */
	private static final int SETTLED_PER_STRUCTURE = 8;
	private static final List<Heightmap.Types> HEIGHT_MAPS = List.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(MuteAudio::mute);
		TestWorlds.resetConfig(context);
		SeedCompare.Stats settled = new SeedCompare.Stats("decorated on the server, start chunk first");
		Map<String, Integer> compared = new TreeMap<>();
		Set<String> placed = new TreeSet<>();
		for (World w : WORLDS) {
			long started = System.nanoTime();
			SeedWorld seedWorld = SeedWorld.create(w.seed(), w.type());
			try (TestSingleplayerContext world = TestWorlds.builder(context, w.seed(), w.type()).create()) {
				TestServerContext server = world.getServer();
				for (ResourceKey<Level> dimension : DIMENSIONS) {
					SeedDimension seed = seedWorld.dimension(dimension);
					String label = w + ", " + dimension.identifier();
					check(seed != null, label + ": seed mode has no such dimension");
					seed.structureState().ensureStructuresGenerated();
					sameStructureSets(server, dimension, seed, label);
					sameTerrain(server, dimension, seed, label);
					Set<Long> chunks = new LinkedHashSet<>();
					for (Area area : AREAS) {
						chunks.addAll(samePlacement(server, dimension, seed, area, label));
					}
					Map<String, List<Long>> starts = sameStarts(server, dimension, seed, new ArrayList<>(chunks), label);
					placed.addAll(locateTheRest(server, dimension, seed, starts, label));
					starts.forEach((id, list) -> compared.merge(id, list.size(), Integer::sum));
					settle(server, dimension, seed, starts, settled, label);
				}
			}
			LOGGER.info("{}: seed mode matches the server, {} s", w, (System.nanoTime() - started) / 1_000_000_000);
		}
		LOGGER.info("Structure starts compared with the server, all worlds: {}", compared);
		settled.log(LOGGER);
		List<String> inexact = settled.tooFewExact();
		check(inexact.isEmpty(), "settled heights too often off: " + inexact);
		Set<String> missing = new TreeSet<>(placed);
		missing.removeAll(compared.keySet());
		check(missing.isEmpty(), "structures no world compared: " + missing);
		check(SeedCompare.SETTLING.stream().allMatch(compared::containsKey), "a settling structure wasn't compared: " + compared.keySet());
	}

	/** The same structure sets, the same origin, and the same stronghold rings. */
	private static void sameStructureSets(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, String label) {
		server.runOnServer(minecraftServer -> {
			ChunkGeneratorStructureState state = minecraftServer.getLevel(dimension).getChunkSource().getGeneratorState();
			ChunkGeneratorStructureState seedState = seed.structureState();
			Map<Identifier, StructurePlacement> placements = placements(state);
			Map<Identifier, StructurePlacement> seedPlacements = placements(seedState);
			check(placements.keySet().equals(seedPlacements.keySet()),
				label + ": structure sets " + seedPlacements.keySet() + " in seed mode, " + placements.keySet() + " on the server");
			check(!placements.isEmpty() || dimension != Level.OVERWORLD, label + ": no structure sets");
			check(state.getDimensionOrigin().equals(seedState.getDimensionOrigin()),
				label + ": origin " + seedState.getDimensionOrigin() + " in seed mode, " + state.getDimensionOrigin() + " on the server");
			int rings = 0;
			for (Map.Entry<Identifier, StructurePlacement> entry : placements.entrySet()) {
				if (entry.getValue() instanceof ConcentricRingsStructurePlacement ringPlacement) {
					List<ChunkPos> positions = state.getRingPositionsFor(ringPlacement);
					List<ChunkPos> seedPositions = seedState.getRingPositionsFor((ConcentricRingsStructurePlacement) seedPlacements.get(entry.getKey()));
					check(positions != null && !positions.isEmpty(), label + ": no " + entry.getKey() + " rings on the server");
					check(positions.equals(seedPositions), label + ": " + entry.getKey() + " rings differ: " + firstDifference(positions, seedPositions));
					rings += positions.size();
				}
			}
			check(dimension != Level.OVERWORLD || rings > 100, label + ": only " + rings + " stronghold ring positions");
			LOGGER.info("{}: {} structure sets, origin {}, {} ring positions, all the server's", label, placements.size(), state.getDimensionOrigin(), rings);
		});
	}

	private static Map<Identifier, StructurePlacement> placements(ChunkGeneratorStructureState state) {
		Map<Identifier, StructurePlacement> placements = new TreeMap<>();
		for (Holder<StructureSet> set : state.possibleStructureSets()) {
			placements.put(set.unwrapKey().orElseThrow().identifier(), set.value().placement());
		}
		return placements;
	}

	private static String firstDifference(List<ChunkPos> server, List<ChunkPos> seed) {
		if (seed == null) {
			return "seed mode has none";
		}
		for (int i = 0; i < Math.min(server.size(), seed.size()); i++) {
			if (!server.get(i).equals(seed.get(i))) {
				return "number " + i + " is " + seed.get(i) + " in seed mode, " + server.get(i) + " on the server";
			}
		}
		return seed.size() + " in seed mode, " + server.size() + " on the server";
	}

	/** The terrain's height and the biomes on a grid in every area, from the seed's generator as from the server's. */
	private static void sameTerrain(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, String label) {
		server.runOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			ServerChunkCache chunks = level.getChunkSource();
			BiomeResolver biomes = chunks.getGenerator().getBiomeSource().createUncachedResolver(chunks.randomState());
			BiomeResolver seedBiomes = seed.generator().getBiomeSource().createUncachedResolver(seed.randomState());
			int[] ys = {level.getMinY() + 8, 40, 100, level.getMaxY() - 8};
			int columns = 0;
			for (Area area : AREAS) {
				for (int i = 0; i < 8; i++) {
					for (int j = 0; j < 8; j++) {
						// 37 blocks apart: not on the chunk grid nor the biome grid.
						int x = area.x() * 16 + (i - 4) * 37 + 5;
						int z = area.z() * 16 + (j - 4) * 37 + 11;
						for (Heightmap.Types type : HEIGHT_MAPS) {
							int height = chunks.getGenerator().getBaseHeight(x, z, type, level, chunks.randomState());
							int seedHeight = seed.height(type, x, z);
							check(height == seedHeight, label + ": " + type + " at " + x + ", " + z + " is " + seedHeight + " in seed mode, " + height + " on the server");
						}
						for (int y : ys) {
							Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
							Holder<Biome> seedBiome = seedBiomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
							check(key(biome).equals(key(seedBiome)),
								label + ": biome at " + x + ", " + y + ", " + z + " is " + key(seedBiome) + " in seed mode, " + key(biome) + " on the server");
						}
						columns++;
					}
				}
			}
			LOGGER.info("{}: terrain height and biomes the server's in {} columns", label, columns);
		});
	}

	private static String key(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("?");
	}

	/**
	 * Every structure set is placed in the same chunks of the area. Returns the chunks to compare the starts of: for
	 * each set, the few nearest the area's middle.
	 */
	private static List<Long> samePlacement(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, Area area, String label) {
		return server.computeOnServer(minecraftServer -> {
			ChunkGeneratorStructureState state = minecraftServer.getLevel(dimension).getChunkSource().getGeneratorState();
			ChunkGeneratorStructureState seedState = seed.structureState();
			Map<Identifier, StructurePlacement> placements = placements(state);
			Map<Identifier, StructurePlacement> seedPlacements = placements(seedState);
			List<Long> picked = new ArrayList<>();
			int placed = 0;
			for (Map.Entry<Identifier, StructurePlacement> entry : placements.entrySet()) {
				StructurePlacement placement = entry.getValue();
				StructurePlacement seedPlacement = seedPlacements.get(entry.getKey());
				List<Long> here = new ArrayList<>();
				for (int x = area.x() - area.radius(); x <= area.x() + area.radius(); x++) {
					for (int z = area.z() - area.radius(); z <= area.z() + area.radius(); z++) {
						boolean onServer = placement.isStructureChunk(state, x, z);
						if (onServer != seedPlacement.isStructureChunk(seedState, x, z)) {
							throw new AssertionError(label + ": " + entry.getKey() + " is " + (onServer ? "" : "not ") + "placed in chunk " + x + ", " + z
								+ " on the server, but " + (onServer ? "not" : "is") + " in seed mode");
						}
						if (onServer) {
							here.add(ChunkPos.pack(x, z));
						}
					}
				}
				placed += here.size();
				here.sort(Comparator.comparingDouble(area::distanceSquared));
				picked.addAll(here.subList(0, Math.min(PER_SET, here.size())));
			}
			int side = 2 * area.radius() + 1;
			LOGGER.info("{}: {} sets placed in the same {} of {} chunks around {}, {}", label, placements.size(), placed, side * side, area.x(), area.z());
			return picked;
		});
	}

	/**
	 * The structures starting in each chunk: the same ones, planned the same. Returns the start chunks of each
	 * structure compared.
	 */
	private static Map<String, List<Long>> sameStarts(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, List<Long> chunks,
			String label) {
		Map<String, List<Long>> starts = new TreeMap<>();
		int empty = 0;
		for (int from = 0; from < chunks.size(); from += BATCH) {
			List<Long> batch = chunks.subList(from, Math.min(chunks.size(), from + BATCH));
			List<Map<String, StructureInfo>> onServer = server.computeOnServer(minecraftServer -> {
				ServerLevel level = minecraftServer.getLevel(dimension);
				List<Map<String, StructureInfo>> all = new ArrayList<>();
				for (long chunk : batch) {
					all.add(serverStarts(level, chunk));
				}
				return all;
			});
			for (int i = 0; i < batch.size(); i++) {
				long chunk = batch.get(i);
				Map<String, StructureInfo> expected = onServer.get(i);
				Map<String, StructureInfo> planned = new TreeMap<>();
				for (SeedDimension.Planned start : seed.plan(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
					planned.put(start.id(), seed.plannedInfo(start));
					boolean settles = SeedCompare.SETTLING.contains(start.id());
					for (StructurePiece piece : start.start().getPieces()) {
						check(SeedSettling.settles(piece) == settles, label + ": " + start.id() + "'s piece " + piece.getClass().getSimpleName()
							+ (settles ? " doesn't settle" : " settles") + " in seed mode");
					}
				}
				check(expected.keySet().equals(planned.keySet()), label + ": chunk " + ChunkPos.unpack(chunk) + " starts " + planned.keySet()
					+ " in seed mode, " + expected.keySet() + " on the server");
				for (Map.Entry<String, StructureInfo> entry : expected.entrySet()) {
					StructureInfo mine = planned.get(entry.getKey());
					check(mine.equals(entry.getValue()), label + ": " + entry.getKey() + " starting in " + ChunkPos.unpack(chunk) + " is planned "
						+ describe(mine) + " in seed mode, " + describe(entry.getValue()) + " on the server");
					starts.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(chunk);
				}
				if (expected.isEmpty()) {
					empty++;
				}
			}
		}
		LOGGER.info("{}: the same starts in {} chunks ({} with none: placed, but the biome or ground didn't suit): {}", label, chunks.size(), empty,
			counts(starts));
		return starts;
	}

	/** Server thread: the valid starts in the chunk, by structure id, generating the chunk that far if need be. */
	private static Map<String, StructureInfo> serverStarts(ServerLevel level, long chunk) {
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Map<String, StructureInfo> starts = new TreeMap<>();
		level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.STRUCTURE_STARTS, true).getAllStarts().forEach((structure, start) -> {
			if (start.isValid()) {
				String id = TestWorlds.id(registry, structure);
				starts.put(id, TestWorlds.serverInfo(level, id, structure, start));
			}
		});
		return starts;
	}

	private static String describe(StructureInfo info) {
		return info.bounds() + " in " + info.pieces().size() + " pieces " + info.kind() + " " + info.spawnArea();
	}

	private static Map<String, Integer> counts(Map<String, List<Long>> starts) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		starts.forEach((id, chunks) -> counts.put(id, chunks.size()));
		return counts;
	}

	/**
	 * For every structure the dimension places that the areas didn't have (or, for those that settle, had fewer than
	 * {@link #SETTLED_PER_STRUCTURE} of), the nearest from each of {@link #LOCATE_FROM}: planned the same. Gives the
	 * ids of every structure the dimension places.
	 */
	private static List<String> locateTheRest(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed,
			Map<String, List<Long>> starts, String label) {
		List<ResourceKey<Structure>> placed = server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
			return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements()
				.filter(holder -> holder.key().identifier().getNamespace().equals("minecraft"))
				.filter(holder -> !state.getPlacementsForStructure(holder).isEmpty())
				.map(Holder.Reference::key)
				.toList();
		});
		Map<String, Integer> located = new TreeMap<>();
		List<String> notFound = new ArrayList<>();
		for (ResourceKey<Structure> key : placed) {
			String id = key.identifier().toString();
			int want = SeedCompare.SETTLING.contains(id) ? SETTLED_PER_STRUCTURE : 1;
			for (BlockPos from : LOCATE_FROM) {
				List<Long> have = starts.computeIfAbsent(id, k -> new ArrayList<>());
				if (have.size() >= want) {
					break;
				}
				StructureInfo found = TestWorlds.tryLocate(server, dimension, key, from);
				if (found == null || have.contains(found.startChunk())) {
					continue;
				}
				SeedCompare.SeedStart mine = SeedCompare.seedStart(seed, found.id(), found.startChunk());
				check(mine != null, label + ": seed mode plans no " + found.id() + " in " + ChunkPos.unpack(found.startChunk()) + ", where locate found one");
				check(mine.planned().equals(found), label + ": the nearest " + found.id() + " to " + from.toShortString() + " is planned "
					+ describe(mine.planned()) + " in seed mode, " + describe(found) + " on the server");
				have.add(found.startChunk());
				located.merge(key.identifier().getPath(), 1, Integer::sum);
			}
			if (starts.get(id).isEmpty()) {
				starts.remove(id);
				notFound.add(key.identifier().getPath());
			}
		}
		LOGGER.info("{}: located {} more, planned the same: {}; none near enough to find: {}", label,
			located.values().stream().mapToInt(Integer::intValue).sum(), located, notFound);
		return placed.stream().map(key -> key.identifier().toString()).toList();
	}

	/**
	 * For a few of each structure that settles: decorates its start chunk and then the rest it covers, on the server,
	 * and compares where its pieces went with where seed mode puts them.
	 */
	private static void settle(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, Map<String, List<Long>> starts,
			SeedCompare.Stats stats, String label) {
		List<String> problems = new ArrayList<>();
		int count = 0;
		for (Map.Entry<String, List<Long>> entry : starts.entrySet()) {
			String id = entry.getKey();
			if (!SeedCompare.SETTLING.contains(id)) {
				continue;
			}
			for (long chunk : entry.getValue().subList(0, Math.min(SETTLED_PER_STRUCTURE, entry.getValue().size()))) {
				SeedCompare.SeedStart mine = Objects.requireNonNull(SeedCompare.seedStart(seed, id, chunk));
				Box planned = mine.planned().bounds();
				SeedCompare.ServerStart real = server.computeOnServer(minecraftServer -> {
					ServerLevel level = minecraftServer.getLevel(dimension);
					level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.FEATURES, true);
					for (int x = planned.minX() >> 4; x <= planned.maxX() >> 4; x++) {
						for (int z = planned.minZ() >> 4; z <= planned.maxZ() >> 4; z++) {
							level.getChunk(x, z, ChunkStatus.FEATURES, true);
						}
					}
					Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(id));
					StructureStart start = level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.STRUCTURE_STARTS, true)
						.getStartForStructure(structure);
					return SeedCompare.serverStart(level, id, structure, start);
				});
				String problem = SeedCompare.difference(real, mine.planned(), mine.settled(), stats);
				if (problem != null) {
					problems.add(problem);
				}
				count++;
			}
		}
		LOGGER.info("{}: {} settling structures decorated and compared", label, count);
		check(problems.isEmpty(), label + ": " + String.join("; ", problems));
	}
}
