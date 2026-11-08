package com.errolmc.boundingboxviewer.gametest;

import static com.errolmc.boundingboxviewer.gametest.TestWorlds.check;

import com.errolmc.boundingboxviewer.client.seed.SeedDimension;
import com.errolmc.boundingboxviewer.client.seed.SeedSettling;
import com.errolmc.boundingboxviewer.client.seed.SeedSnapping;
import com.errolmc.boundingboxviewer.client.seed.SeedWorld;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.WorldType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed mode against the server, world by world: seven worlds (five seeds of the default type, one with large biomes, one
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
 * The areas are far from spawn, so nothing in them is decorated until the settling part does it. With
 * {@link SeedSurvey#ACTIVE} ({@code gradlew seedSurvey}) it runs on the survey's seeds instead, writing every settled
 * piece down and carrying on past what's wrong.
 */
public class SeedPlanningClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer-gametest");
	private static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

	record World(long seed, WorldType type) {
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
	/** Structures that settle decorated and compared per structure and dimension (the survey sets its own). */
	private static final int SETTLED_PER_STRUCTURE = 8;
	/**
	 * Settling structures decorated per server task. The start chunks of a batch are asked for all at once, so the
	 * worldgen threads decorate them side by side, then the rest of the chunks they cover.
	 */
	private static final int SETTLE_BATCH = 16;
	/** Start chunks per structure whose terrain is compared block for block. */
	private static final int TERRAIN_PER_STRUCTURE = 2;
	private static final List<Heightmap.Types> HEIGHT_MAPS = List.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(MuteAudio::mute);
		TestWorlds.resetConfig(context);
		ChunkSaving.skipDuring(() -> {
			if (SeedSurvey.ACTIVE) {
				survey(context);
			} else {
				compareAll(context);
			}
		});
	}

	private static void compareAll(ClientGameTestContext context) {
		SeedCompare.Stats settled = new SeedCompare.Stats("decorated on the server, start chunk first");
		SeedCompare.Snaps snaps = new SeedCompare.Snaps("decorated on the server");
		List<String> snapProblems = new ArrayList<>();
		Map<String, Integer> compared = new TreeMap<>();
		Set<String> placed = new TreeSet<>();
		for (World w : WORLDS) {
			long started = System.nanoTime();
			SeedWorld seedWorld = SeedWorld.create(w.seed(), w.type());
			try (TestSingleplayerContext world = TestWorlds.builder(context, w.seed(), w.type()).create()) {
				compareWorld(world.getServer(), seedWorld, w, SETTLED_PER_STRUCTURE, LOCATE_FROM, settled, snaps, snapProblems, compared, placed, null);
			}
			LOGGER.info("{}: seed mode matches the server, {} s", w, (System.nanoTime() - started) / 1_000_000_000);
		}
		LOGGER.info("Structure starts compared with the server, all worlds: {}", compared);
		settled.log(LOGGER);
		snaps.log(LOGGER);
		snapProblems.forEach(problem -> LOGGER.warn("Seed mode put on the blocks: {}", problem));
		List<String> inexact = settled.tooFewExact();
		check(inexact.isEmpty(), "settled heights too often off: " + inexact);
		Set<String> missing = new TreeSet<>(placed);
		missing.removeAll(compared.keySet());
		check(missing.isEmpty(), "structures no world compared: " + missing);
		check(SeedCompare.SETTLING.stream().allMatch(compared::containsKey), "a settling structure wasn't compared: " + compared.keySet());
		check(snapProblems.isEmpty(), "seed mode put on the blocks: " + String.join("; ", snapProblems));
	}

	/**
	 * The survey: each of its worlds compared as above, every settled piece written down. A world that goes wrong is
	 * written down and left, and the next one goes on; each world's save is deleted once it's closed.
	 */
	private static void survey(ClientGameTestContext context) {
		SeedSurvey survey = SeedSurvey.open();
		List<Long> seeds = survey.seeds();
		LOGGER.info("Seed survey, instance {}: {} {} worlds, up to {} of each settling structure per world", survey.instance(), seeds.size(),
			survey.type().key(), survey.perStructure());
		long surveyStarted = System.nanoTime();
		for (int i = 0; i < seeds.size(); i++) {
			World w = new World(seeds.get(i), survey.type());
			long started = System.nanoTime();
			SeedCompare.Stats stats = new SeedCompare.Stats(w.toString());
			SeedCompare.Snaps snaps = new SeedCompare.Snaps(w.toString());
			List<String> problems = new ArrayList<>();
			String result;
			Path save = null;
			try {
				SeedWorld seedWorld = SeedWorld.create(w.seed(), w.type());
				List<BlockPos> from = new ArrayList<>(LOCATE_FROM);
				from.addAll(SeedSurvey.moreLocateFrom(w.seed()));
				try (TestSingleplayerContext world = TestWorlds.builder(context, w.seed(), w.type()).create()) {
					TestServerContext server = world.getServer();
					save = server.computeOnServer(minecraftServer -> minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
					List<String> snapProblems = new ArrayList<>();
					problems.addAll(compareWorld(server, seedWorld, w, survey.perStructure(), from, stats, snaps, snapProblems, new TreeMap<>(), new TreeSet<>(),
						survey));
					problems.addAll(snapProblems);
				}
				result = problems.isEmpty() ? "ok" : "over limits";
			} catch (AssertionError | RuntimeException e) {
				LOGGER.error("{}: survey stopped for this world", w, e);
				problems.add(e.toString());
				result = "error";
			}
			long seconds = (System.nanoTime() - started) / 1_000_000_000;
			survey.world(w, seconds, stats.count(), result, problems);
			LOGGER.info("Seed survey, instance {}: world {} of {}, {}: {} settled pieces, {}, {} s", survey.instance(), i + 1, seeds.size(), w,
				stats.count(), result, seconds);
			deleteSave(save);
		}
		survey.close();
		LOGGER.info("Seed survey, instance {}: {} worlds in {} min", survey.instance(), seeds.size(),
			(System.nanoTime() - surveyStarted) / 60_000_000_000L);
	}

	/** Deletes a closed world's folder, so a long survey doesn't fill the disk (a world is about 100 MB). */
	private static void deleteSave(Path save) {
		if (save == null || !Files.isDirectory(save)) {
			return;
		}
		try (Stream<Path> files = Files.walk(save)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			LOGGER.warn("Couldn't delete the survey world {}", save, e);
		}
	}

	/**
	 * Every comparison for one world, each dimension. Settling structures too far off (over {@link SeedCompare#LIMITS})
	 * fail the test or, with a survey, are given back; the survey also gets every settled piece. Each is put on its
	 * blocks too, as seed mode does once the client has its chunks: what's wrong with that goes to {@code snapProblems}.
	 */
	private static List<String> compareWorld(TestServerContext server, SeedWorld seedWorld, World w, int perStructure, List<BlockPos> locateFrom,
			SeedCompare.Stats stats, SeedCompare.Snaps snaps, List<String> snapProblems, Map<String, Integer> compared, Set<String> placed,
			SeedSurvey survey) {
		List<String> problems = new ArrayList<>();
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
			placed.addAll(locateTheRest(server, dimension, seed, starts, perStructure, locateFrom, label));
			starts.forEach((id, list) -> compared.merge(id, list.size(), Integer::sum));
			List<String> here = new ArrayList<>(sameCarvedTerrain(server, dimension, seed, starts, label));
			List<Settled> settled = settle(server, dimension, seed, starts, perStructure);
			for (Settled one : settled) {
				String problem = SeedCompare.difference(one.real(), one.mine().planned(), one.mine().settled(), stats);
				if (problem != null) {
					here.add(problem);
				}
				SeedSnapping.Snapped snapped = one.blocks() != null ? SeedSnapping.snap(seed, one.mine().settled(), one.blocks()) : null;
				String snapProblem = snapped != null ? SeedCompare.snapDifference(seed, one.real(), one.mine().settled(), snapped, one.blocks(), snaps) : null;
				if (snapProblem != null) {
					snapProblems.add(label + ": " + snapProblem);
				}
				if (survey != null) {
					survey.structure(w, dimension, one.real(), one.mine(), snapped, one.biomes());
				}
			}
			LOGGER.info("{}: {} settling structures decorated and compared", label, settled.size());
			if (survey == null) {
				check(here.isEmpty(), label + ": " + String.join("; ", here));
			}
			here.forEach(problem -> problems.add(label + ": " + problem));
		}
		return problems;
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
					check(samePlan(server, dimension, mine, entry.getValue()), label + ": " + entry.getKey() + " starting in " + ChunkPos.unpack(chunk) + " is planned "
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

	/**
	 * Seed mode's plan is the server's start. A structure that settles may have settled already where the server has
	 * decorated its chunks (around spawn, which on some seeds is 2,000 blocks out): such a piece needs only the same
	 * footprint, how far it moved being for the settling comparison.
	 */
	private static boolean samePlan(TestServerContext server, ResourceKey<Level> dimension, StructureInfo mine, StructureInfo real) {
		if (mine.equals(real)) {
			return true;
		}
		if (!SeedCompare.SETTLING.contains(real.id()) || !mine.id().equals(real.id()) || mine.kind() != real.kind() || mine.startChunk() != real.startChunk()
				|| !Objects.equals(mine.spawnArea(), real.spawnArea()) || mine.pieces().size() != real.pieces().size()) {
			return false;
		}
		List<Boolean> decorated = server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			return real.pieces().stream()
				.map(piece -> SeedCompare.decorated(level, new BoundingBox(piece.minX(), piece.minY(), piece.minZ(), piece.maxX(), piece.maxY(), piece.maxZ())))
				.toList();
		});
		for (int i = 0; i < real.pieces().size(); i++) {
			Box planned = mine.pieces().get(i);
			Box there = real.pieces().get(i);
			if (!planned.equals(there) && !(decorated.get(i) && SeedCompare.sameFootprint(planned, there))) {
				return false;
			}
		}
		return true;
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
	 * {@code perStructure} of), the nearest from each place in {@code locateFrom} until there are enough: planned the
	 * same. Gives the ids of every structure the dimension places.
	 */
	private static List<String> locateTheRest(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed,
			Map<String, List<Long>> starts, int perStructure, List<BlockPos> locateFrom, String label) {
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
			int want = SeedCompare.SETTLING.contains(id) ? perStructure : 1;
			for (BlockPos from : locateFrom) {
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
				check(samePlan(server, dimension, mine.planned(), found), label + ": the nearest " + found.id() + " to " + from.toShortString() + " is planned "
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
	 * Seed mode's ground before decorating is the server's terrain step, block for block and in every height map: a
	 * chunk's terrain is its noise, surface, caves and ravines, and the beard of the structures that reshape the ground
	 * near it. Compared in the middle 2 x 2 chunks of each area and the start chunks of up to
	 * {@value #TERRAIN_PER_STRUCTURE} of each structure (so villages, outposts and the rest shape some, and the settling
	 * structures sit on them), each before the server decorates it or a chunk next to it. Gives what's different.
	 */
	private static List<String> sameCarvedTerrain(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, Map<String, List<Long>> starts,
			String label) {
		Set<Long> chunks = new LinkedHashSet<>();
		for (Area area : AREAS) {
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					chunks.add(ChunkPos.pack(area.x() + dx, area.z() + dz));
				}
			}
		}
		starts.values().forEach(list -> chunks.addAll(list.subList(0, Math.min(TERRAIN_PER_STRUCTURE, list.size()))));
		long started = System.nanoTime();
		int[] counts = new int[2];
		List<String> problems = server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			List<String> found = new ArrayList<>();
			for (long chunk : chunks) {
				int chunkX = ChunkPos.getX(chunk);
				int chunkZ = ChunkPos.getZ(chunk);
				// Decorating a chunk writes into the chunks next to it, so those mustn't be decorated either.
				BoundingBox around = new BoundingBox((chunkX - 1) * 16, level.getMinY(), (chunkZ - 1) * 16, chunkX * 16 + 31, level.getMaxY(), chunkZ * 16 + 31);
				ChunkAccess real = SeedCompare.decorated(level, around) ? null : level.getChunk(chunkX, chunkZ, ChunkStatus.TERRAIN, true);
				if (real == null || real.getPersistedStatus() != ChunkStatus.TERRAIN) {
					counts[1]++;
					continue;
				}
				counts[0]++;
				String problem = terrainDifference(level, real, seed);
				if (problem != null) {
					found.add("terrain in chunk " + chunkX + ", " + chunkZ + ": " + problem);
				}
			}
			return found;
		});
		LOGGER.info("{}: terrain before decorating {} in {} chunks ({} decorated already, not compared), {} ms", label,
			problems.isEmpty() ? "the server's" : problems.size() + " different", counts[0], counts[1], (System.nanoTime() - started) / 1_000_000);
		problems.forEach(problem -> LOGGER.warn("{}: {}", label, problem));
		return problems;
	}

	/** Server thread: how seed mode's version of the chunk differs from the server's, or null when it doesn't. */
	private static String terrainDifference(ServerLevel level, ChunkAccess real, SeedDimension seed) {
		int minX = real.getPos().getMinBlockX();
		int minZ = real.getPos().getMinBlockZ();
		int blocks = 0;
		String firstBlock = null;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int y = level.getMinY(); y <= level.getMaxY(); y++) {
			for (int x = minX; x < minX + 16; x++) {
				for (int z = minZ; z < minZ + 16; z++) {
					BlockState there = real.getBlockState(at.set(x, y, z));
					BlockState mine = seed.groundBlock(x, y, z);
					if (there != mine) {
						if (blocks++ == 0) {
							firstBlock = "first at " + x + ", " + y + ", " + z + ": " + mine + " in seed mode, " + there + " on the server";
						}
					}
				}
			}
		}
		Map<Heightmap.Types, Integer> heights = new TreeMap<>();
		String firstHeight = null;
		for (Heightmap.Types type : Heightmap.Types.values()) {
			for (int x = minX; x < minX + 16; x++) {
				for (int z = minZ; z < minZ + 16; z++) {
					int there = real.getHeight(type, x, z) + 1;
					int mine = seed.ground(type, x, z);
					if (there != mine) {
						heights.merge(type, 1, Integer::sum);
						if (firstHeight == null) {
							firstHeight = "first " + type + " at " + x + ", " + z + ": " + mine + " in seed mode, " + there + " on the server";
						}
					}
				}
			}
		}
		if (blocks == 0 && heights.isEmpty()) {
			return null;
		}
		return blocks + " blocks different" + (firstBlock != null ? " (" + firstBlock + ")" : "") + ", height map columns different " + heights
			+ (firstHeight != null ? " (" + firstHeight + ")" : "");
	}

	/**
	 * A settling structure decorated on the server, seed mode's version of it, the biome at each piece, and the blocks
	 * under seed mode's pieces (null for buried treasure, or a chunk not decorated).
	 */
	private record Settled(SeedCompare.ServerStart real, SeedCompare.SeedStart mine, List<String> biomes, SeedSnapping.Columns blocks) {
	}

	/** A settling structure to decorate: its id, start chunk, seed mode's version, and the chunks its planned box covers. */
	private record ToSettle(String id, long chunk, SeedCompare.SeedStart mine, Set<Long> covers) {
	}

	/**
	 * For up to {@code perStructure} of each structure that settles: decorates its start chunk and then the rest it
	 * covers, on the server, and gives where its pieces went next to where seed mode puts them. In batches: a batch's
	 * start chunks all at once, then the rest of its chunks. A structure whose start chunk another in the batch covers
	 * (or which covers another's start chunk) waits for a later batch, so every start chunk is decorated before the
	 * other chunks its structure covers, as when done one by one.
	 */
	private static List<Settled> settle(TestServerContext server, ResourceKey<Level> dimension, SeedDimension seed, Map<String, List<Long>> starts,
			int perStructure) {
		List<ToSettle> todo = new ArrayList<>();
		for (Map.Entry<String, List<Long>> entry : starts.entrySet()) {
			String id = entry.getKey();
			if (!SeedCompare.SETTLING.contains(id)) {
				continue;
			}
			for (long chunk : entry.getValue().subList(0, Math.min(perStructure, entry.getValue().size()))) {
				SeedCompare.SeedStart mine = Objects.requireNonNull(SeedCompare.seedStart(seed, id, chunk));
				Box planned = mine.planned().bounds();
				Set<Long> covers = new LinkedHashSet<>();
				covers.add(chunk);
				for (int x = planned.minX() >> 4; x <= planned.maxX() >> 4; x++) {
					for (int z = planned.minZ() >> 4; z <= planned.maxZ() >> 4; z++) {
						covers.add(ChunkPos.pack(x, z));
					}
				}
				todo.add(new ToSettle(id, chunk, mine, covers));
			}
		}
		List<Settled> settled = new ArrayList<>();
		while (!todo.isEmpty()) {
			List<ToSettle> batch = new ArrayList<>();
			List<ToSettle> later = new ArrayList<>();
			for (ToSettle one : todo) {
				boolean clashes = batch.stream().anyMatch(other -> other.covers().contains(one.chunk()) || one.covers().contains(other.chunk()));
				if (batch.size() < SETTLE_BATCH && !clashes) {
					batch.add(one);
				} else {
					later.add(one);
				}
			}
			todo = later;
			settled.addAll(server.computeOnServer(minecraftServer -> {
				ServerLevel level = minecraftServer.getLevel(dimension);
				Set<Long> startChunks = new LinkedHashSet<>();
				batch.forEach(one -> startChunks.add(one.chunk()));
				decorate(level, startChunks);
				Set<Long> rest = new LinkedHashSet<>();
				batch.forEach(one -> rest.addAll(one.covers()));
				rest.removeAll(startChunks);
				decorate(level, rest);
				Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
				List<Settled> done = new ArrayList<>();
				for (ToSettle one : batch) {
					Structure structure = registry.getValue(Identifier.parse(one.id()));
					StructureStart start = level.getChunk(ChunkPos.getX(one.chunk()), ChunkPos.getZ(one.chunk()), ChunkStatus.STRUCTURE_STARTS, true)
						.getStartForStructure(structure);
					SeedCompare.ServerStart real = SeedCompare.serverStart(level, one.id(), structure, start);
					List<String> biomes = new ArrayList<>();
					for (Box piece : real.info().pieces()) {
						biomes.add(key(level.getBiome(BlockPos.containing(piece.centerX(), piece.minY(), piece.centerZ()))));
					}
					SeedSnapping.Columns blocks = SeedCompare.snaps(one.id()) ? SeedCompare.serverBlocks(level, one.mine().settled()) : null;
					done.add(new Settled(real, one.mine(), biomes, blocks));
				}
				return done;
			}));
		}
		return settled;
	}

	/**
	 * Server thread: decorates the chunks side by side. Asks for them all (each request queues the chunk, and what it
	 * needs around it, for the worldgen threads), then waits for each; the server doesn't tick meanwhile, so the
	 * requests don't lapse.
	 */
	private static void decorate(ServerLevel level, Collection<Long> chunks) {
		ServerChunkCache source = level.getChunkSource();
		for (long chunk : chunks) {
			source.getChunkFuture(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.FEATURES, true);
		}
		for (long chunk : chunks) {
			level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.FEATURES, true);
		}
	}
}
