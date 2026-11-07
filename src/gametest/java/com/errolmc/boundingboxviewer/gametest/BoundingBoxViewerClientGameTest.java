package com.errolmc.boundingboxviewer.gametest;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.input.BoxKeys;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.overlay.Labels;
import com.errolmc.boundingboxviewer.client.world.StructureCollector;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureStore;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.SpawnArea;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a default world with a known seed and turns the boxes on with the real B key. Then, for every structure in
 * the game's registry ({@code minecraft:} ones, in whichever dimension's generator places them), it finds the nearest
 * with the game's own locate code, goes there and checks the box drawn is the server's structure start as it is once
 * the chunks around it are loaded: the same box, the same pieces, the right spawn area, a kind of its own. In every
 * place it also checks that every structure the loaded chunks start or refer to is known, including those starting in
 * chunks that aren't loaded. At the swamp hut, the settings: hiding a kind, pieces off, range. Screenshots land in
 * build/run/clientGameTest/screenshots.
 */
public class BoundingBoxViewerClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer-gametest");
	private static final long SEED = 607;
	/** Chunks around the player whose structures must all be known. */
	private static final int CHECK_RADIUS = 5;
	/** Comparisons with the server are tried again a few times, a second apart, while chunks finish loading. */
	private static final int ATTEMPTS = 10;
	/** Locate's search radius, as {@code /locate} uses. */
	private static final int LOCATE_RADIUS = 100;
	private static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);
	/** Vanilla's structures whose own mobs spawn in them (from their spawn overrides); every other has none. */
	private static final Map<String, SpawnArea> SPAWN_AREAS = Map.of(
		"minecraft:swamp_hut", SpawnArea.PIECES,
		"minecraft:fortress", SpawnArea.PIECES,
		"minecraft:pillager_outpost", SpawnArea.WHOLE,
		"minecraft:monument", SpawnArea.WHOLE);
	/** Mostly underground or underwater: a second screenshot drawn through blocks. */
	private static final Set<String> SEE_THROUGH_SHOTS = Set.of("minecraft:stronghold", "minecraft:mineshaft", "minecraft:mineshaft_mesa",
		"minecraft:ancient_city", "minecraft:trial_chambers", "minecraft:trail_ruins", "minecraft:buried_treasure", "minecraft:monument",
		"minecraft:fortress", "minecraft:ocean_ruin_cold", "minecraft:shipwreck");

	/** A structure as the server has it. */
	private record Found(String id, long chunk, Box bounds, List<Box> pieces) {
	}

	/** A structure to visit, and the dimension it generates in. */
	private record Target(ResourceKey<Level> dimension, ResourceKey<Structure> key) {
		String id() {
			return key.identifier().toString();
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(MuteAudio::mute);
		context.runOnClient(minecraft -> {
			BoxConfig defaults = new BoxConfig();
			BoxConfig config = BoxConfig.get();
			config.range = defaults.range;
			config.showPieces = defaults.showPieces;
			config.showLabels = defaults.showLabels;
			config.shadeSpawnAreas = defaults.shadeSpawnAreas;
			config.seeThrough = defaults.seeThrough;
			config.hidden.clear();
		});
		try (TestSingleplayerContext world = context.worldBuilder()
				.adjustSettings(settings -> {
					settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings().worldgenLoadContext()
						.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL)));
					settings.setGenerateStructures(true);
					settings.setSeed(Long.toString(SEED));
				})
				.create()) {
			world.getConnection().waitForChunksRender();
			// Spectator: a camera inside the rock (the Nether) sees through it rather than the in-a-wall overlay.
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("time set noon");

			check(!context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "boxes start on");
			pressToggle(context);
			check(context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "B did not turn the boxes on");
			allLoadedStructuresKnown(context, world, Level.OVERWORLD);

			List<Target> targets = targets(world);
			LOGGER.info("{} structures to visit: {}", targets.size(), targets.stream().map(Target::id).toList());
			Set<StructureKind> covered = EnumSet.noneOf(StructureKind.class);
			targets.forEach(target -> covered.add(StructureKind.of(target.id())));
			Set<StructureKind> uncovered = EnumSet.complementOf(EnumSet.copyOf(covered));
			uncovered.remove(StructureKind.OTHER);
			check(uncovered.isEmpty(), "kinds no vanilla structure maps to: " + uncovered);
			check(SPAWN_AREAS.keySet().stream().allMatch(id -> targets.stream().anyMatch(target -> target.id().equals(id))),
				"a structure with a spawn area isn't in the registry");

			for (Target target : targets) {
				Found found = visit(context, world, target);
				if (found.id().equals("minecraft:swamp_hut")) {
					check(found.pieces().size() == 1 && found.pieces().getFirst().equals(found.bounds()), "a swamp hut is one piece: " + found);
					settings(context, world, found);
				} else if (found.id().equals("minecraft:fortress")) {
					check(found.pieces().size() > 10, "a fortress has many pieces: " + found.pieces().size());
				}
			}

			pressToggle(context);
			check(!context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "B did not turn the boxes off");
			check(context.computeOnClient(minecraft -> BoxOverlay.shown().isEmpty()), "boxes left after turning them off");
		}
		check(context.computeOnClient(minecraft -> StructureStore.in(Level.OVERWORLD).isEmpty()), "structures kept after the world closed");
		check(context.computeOnClient(minecraft -> minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).get()) == 0.0,
			"test client is not muted");
	}

	/**
	 * Every {@code minecraft:} structure in the registry, with the dimension whose generator places it, Overworld first,
	 * then by id.
	 */
	private static List<Target> targets(TestSingleplayerContext world) {
		return world.getServer().computeOnServer(server -> {
			List<Target> targets = new ArrayList<>();
			List<String> nowhere = new ArrayList<>();
			Registry<Structure> registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			registry.listElements()
				.filter(holder -> holder.key().identifier().getNamespace().equals("minecraft"))
				.sorted(Comparator.comparing(holder -> holder.key().identifier().toString()))
				.forEach(holder -> {
					for (ResourceKey<Level> dimension : DIMENSIONS) {
						if (!server.getLevel(dimension).getChunkSource().getGeneratorState().getPlacementsForStructure(holder).isEmpty()) {
							targets.add(new Target(dimension, holder.key()));
							return;
						}
					}
					nowhere.add(holder.key().identifier().toString());
				});
			check(nowhere.isEmpty(), "structures no dimension places: " + nowhere);
			targets.sort(Comparator.comparingInt((Target target) -> DIMENSIONS.indexOf(target.dimension())));
			return targets;
		});
	}

	/**
	 * Locates the nearest structure from the middle of its dimension, goes there, and checks its box is drawn and
	 * matches the server's. Returns the server's version.
	 */
	private static Found visit(ClientGameTestContext context, TestSingleplayerContext world, Target target) {
		ResourceKey<Level> dimension = target.dimension();
		ResourceKey<Structure> key = target.key();
		// End cities are on the outer islands, 1,000 blocks and more from the middle.
		BlockPos from = dimension == Level.END ? new BlockPos(1500, 64, 0) : BlockPos.ZERO;
		Found planned = locate(world, dimension, key, from);
		Box bounds = planned.bounds();
		LOGGER.info("{}: start chunk {}, planned box {}, {} pieces", planned.id(), ChunkPos.unpack(planned.chunk()), bounds, planned.pieces().size());
		// Over the near (north) side of the box, looking south across it and down: 12 above its top and, outside the
		// Nether, at least 20 above the ground there, so structures underground are seen from the open air. The
		// ground from the generator: the level's heightmap reads as the bottom of the world where nothing is loaded yet.
		double x = bounds.centerX();
		double z = bounds.minZ() - 10;
		int y = world.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dimension);
			int overTop = bounds.top() + 12;
			if (level.dimensionType().hasCeiling()) {
				return overTop;
			}
			ServerChunkCache chunks = level.getChunkSource();
			int ground = chunks.getGenerator().getBaseHeight((int) Math.floor(x), (int) Math.floor(z), Heightmap.Types.MOTION_BLOCKING, level,
				chunks.randomState());
			return Math.max(overTop, ground + 20);
		});
		teleport(context, world, dimension, x, y, z, 0, 35);

		context.waitFor(minecraft -> StructureCollector.pending() == 0 && shown(planned).isPresent(), 600);
		Found found = matchServer(context, () -> current(world, dimension, key, planned.chunk()),
			now -> context.computeOnClient(minecraft -> shown(now)).orElse(null));
		if (!found.bounds().equals(planned.bounds())) {
			LOGGER.info("{} settled onto the ground: box {}", found.id(), found.bounds());
		}
		StructureInfo info = context.computeOnClient(minecraft -> shown(found)).orElseThrow();
		SpawnArea spawnArea = SPAWN_AREAS.getOrDefault(found.id(), SpawnArea.NONE);
		check(info.spawnArea() == spawnArea, found.id() + " spawn area " + info.spawnArea() + ", expected " + spawnArea);
		check(info.kind() == StructureKind.of(found.id()) && info.kind() != StructureKind.OTHER, found.id() + " kind " + info.kind());
		LOGGER.info("{} is shown as \"{}\": box {}, {} pieces", found.id(), context.computeOnClient(minecraft -> Labels.of(info)),
			found.bounds(), found.pieces().size());
		allLoadedStructuresKnown(context, world, dimension);

		String screenshot = key.identifier().getPath();
		context.takeScreenshot(screenshot);
		if (SEE_THROUGH_SHOTS.contains(found.id())) {
			setConfig(context, config -> config.seeThrough = true);
			context.takeScreenshot(screenshot + "-see-through");
			setConfig(context, config -> config.seeThrough = false);
		}
		return found;
	}

	/**
	 * Reads the server's version and the store's until they agree (pieces can still be settling onto the ground while
	 * chunks at the edge load), failing after {@link #ATTEMPTS}.
	 */
	private static Found matchServer(ClientGameTestContext context, Supplier<Found> server, Function<Found, StructureInfo> client) {
		String problem = null;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			if (attempt > 0) {
				context.waitTicks(20);
			}
			context.waitFor(minecraft -> StructureCollector.pending() == 0, 600);
			context.waitTicks(2);
			Found found = server.get();
			problem = difference(found, client.apply(found));
			if (problem == null) {
				return found;
			}
		}
		throw new AssertionError(problem);
	}

	/** What's wrong with the store's version of a structure, or null when it's the server's. */
	private static String difference(Found found, StructureInfo info) {
		String at = found.id() + " starting in " + ChunkPos.unpack(found.chunk());
		if (info == null) {
			return "missing " + at;
		}
		if (!info.bounds().equals(found.bounds())) {
			return at + " box " + info.bounds() + ", server has " + found.bounds();
		}
		if (!info.pieces().equals(found.pieces())) {
			return at + " pieces differ from the server's";
		}
		for (Box piece : info.pieces()) {
			if (!info.bounds().encloses(piece)) {
				return at + " box " + info.bounds() + " doesn't hold its piece " + piece;
			}
		}
		return null;
	}

	/** Hiding a kind takes its boxes away; pieces off and shading off still draw the structure; out of range doesn't. */
	private static void settings(ClientGameTestContext context, TestSingleplayerContext world, Found hut) {
		setConfig(context, config -> config.setShown(StructureKind.SWAMP_HUT, false));
		check(context.computeOnClient(minecraft -> shown(hut)).isEmpty(), "swamp hut shown while swamp huts are hidden");
		setConfig(context, config -> config.setShown(StructureKind.SWAMP_HUT, true));
		check(context.computeOnClient(minecraft -> shown(hut)).isPresent(), "swamp hut not back after showing swamp huts again");

		setConfig(context, config -> {
			config.showPieces = false;
			config.shadeSpawnAreas = false;
			config.showLabels = false;
		});
		check(context.computeOnClient(minecraft -> shown(hut)).isPresent(), "swamp hut gone with pieces, shading and names off");
		context.takeScreenshot("swamp-hut-plain");
		setConfig(context, config -> {
			BoxConfig defaults = new BoxConfig();
			config.showPieces = defaults.showPieces;
			config.shadeSpawnAreas = defaults.shadeSpawnAreas;
			config.showLabels = defaults.showLabels;
		});

		// The test stands 10 blocks north of the hut's box; step 30 further back and the hut is out of the smallest range.
		world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~-30");
		context.waitTicks(5);
		double away = context.computeOnClient(minecraft -> {
			Vec3 eye = minecraft.player.getEyePosition();
			return Math.sqrt(hut.bounds().horizontalDistanceSquared(eye.x, eye.z));
		});
		check(away > BoxConfig.MIN_RANGE, "not far enough from the swamp hut to check the range: " + away);
		int range = new BoxConfig().range;
		setConfig(context, config -> config.range = BoxConfig.MIN_RANGE);
		check(context.computeOnClient(minecraft -> shown(hut)).isEmpty(), "swamp hut " + away + " blocks away shown at range " + BoxConfig.MIN_RANGE);
		setConfig(context, config -> config.range = range);
		check(context.computeOnClient(minecraft -> shown(hut)).isPresent(), "swamp hut not back at range " + range);
	}

	private static void setConfig(ClientGameTestContext context, Consumer<BoxConfig> change) {
		context.runOnClient(minecraft -> {
			change.accept(BoxConfig.get());
			BoxOverlay.rebuildSoon();
		});
		context.waitTicks(2);
	}

	/**
	 * Every structure that the full chunks around the player start, or refer to (starting in a chunk that may not be
	 * loaded), is in the store with the server's box and pieces.
	 */
	private static void allLoadedStructuresKnown(ClientGameTestContext context, TestSingleplayerContext world, ResourceKey<Level> dimension) {
		String problems = null;
		int count = 0;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			if (attempt > 0) {
				context.waitTicks(20);
			}
			context.waitFor(minecraft -> StructureCollector.pending() == 0, 600);
			context.waitTicks(2);
			Map<String, Found> expected = loadedStructures(world, dimension);
			List<String> wrong = new ArrayList<>();
			for (Found found : expected.values()) {
				String problem = difference(found, StructureStore.get(dimension, found.id(), found.chunk()));
				if (problem != null) {
					wrong.add(problem);
				}
			}
			count = expected.size();
			problems = wrong.isEmpty() ? null : String.join("; ", wrong);
			if (problems == null) {
				break;
			}
		}
		check(problems == null, problems);
		LOGGER.info("{}: {} structures in the {} x {} chunks around the player, {} known in the dimension",
			dimension.identifier(), count, 2 * CHECK_RADIUS + 1, 2 * CHECK_RADIUS + 1, StructureStore.in(dimension).size());
	}

	private static Map<String, Found> loadedStructures(TestSingleplayerContext world, ResourceKey<Level> dimension) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dimension);
			Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			ChunkPos center = ChunkPos.containing(server.getPlayerList().getPlayers().getFirst().blockPosition());
			Map<String, Found> all = new LinkedHashMap<>();
			for (int cx = center.x() - CHECK_RADIUS; cx <= center.x() + CHECK_RADIUS; cx++) {
				for (int cz = center.z() - CHECK_RADIUS; cz <= center.z() + CHECK_RADIUS; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					chunk.getAllStarts().forEach((structure, start) -> add(all, registry, structure, start));
					for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
						for (LongIterator it = entry.getValue().iterator(); it.hasNext(); ) {
							long ref = it.nextLong();
							StructureStart start = level.getChunk(ChunkPos.getX(ref), ChunkPos.getZ(ref), ChunkStatus.STRUCTURE_STARTS, true)
								.getStartForStructure(entry.getKey());
							if (start != null) {
								add(all, registry, entry.getKey(), start);
							}
						}
					}
				}
			}
			return all;
		});
	}

	private static void add(Map<String, Found> all, Registry<Structure> registry, Structure structure, StructureStart start) {
		if (start.isValid()) {
			Found found = found(registry.getKey(structure).toString(), structure, start);
			all.putIfAbsent(found.id() + "@" + found.chunk(), found);
		}
	}

	private static Found locate(TestSingleplayerContext world, ResourceKey<Level> dimension, ResourceKey<Structure> key, BlockPos from) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dimension);
			Holder<Structure> holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
			Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, HolderSet.direct(holder), from, LOCATE_RADIUS, false);
			if (nearest == null) {
				throw new AssertionError("locate found no " + key.identifier() + " from " + from);
			}
			ChunkPos chunk = ChunkPos.containing(nearest.getFirst());
			StructureStart start = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS, true).getStartForStructure(holder.value());
			if (start == null || !start.isValid()) {
				throw new AssertionError("locate found " + key.identifier() + " in " + chunk + " but the chunk has no start");
			}
			return found(key.identifier().toString(), holder.value(), start);
		});
	}

	/** The server's start now. */
	private static Found current(TestSingleplayerContext world, ResourceKey<Level> dimension, ResourceKey<Structure> key, long chunk) {
		return world.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(dimension);
			Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(key);
			StructureStart start = level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ChunkStatus.STRUCTURE_STARTS, true)
				.getStartForStructure(structure);
			if (start == null || !start.isValid()) {
				throw new AssertionError("the start of " + key.identifier() + " in " + ChunkPos.unpack(chunk) + " is gone");
			}
			return found(key.identifier().toString(), structure, start);
		});
	}

	/**
	 * The server's start as the game would see it freshly loaded: its pieces where they are now and the box around
	 * them, adjusted the game's way (12 blocks bigger for structures that reshape the ground).
	 */
	private static Found found(String id, Structure structure, StructureStart start) {
		List<Box> pieces = new ArrayList<>();
		List<BoundingBox> boxes = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			boxes.add(piece.getBoundingBox());
			pieces.add(box(piece.getBoundingBox()));
		}
		BoundingBox bounds = structure.adjustBoundingBox(BoundingBox.encapsulatingBoxes(boxes).orElseThrow());
		return new Found(id, start.getChunkPos().pack(), box(bounds), List.copyOf(pieces));
	}

	private static Box box(BoundingBox box) {
		return new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
	}

	private static Optional<StructureInfo> shown(Found found) {
		return BoxOverlay.shown().stream().filter(info -> info.id().equals(found.id()) && info.startChunk() == found.chunk()).findFirst();
	}

	private static void pressToggle(ClientGameTestContext context) {
		context.getInput().pressKey(BoxKeys.TOGGLE);
		context.waitTicks(10);
	}

	private static void teleport(ClientGameTestContext context, TestSingleplayerContext world, ResourceKey<Level> dimension,
			double x, double y, double z, float yaw, float pitch) {
		world.getServer().runCommand("execute in " + dimension.identifier() + " run tp @a " + x + " " + y + " " + z + " " + yaw + " " + pitch);
		// Flying, so the view stays put for the screenshots.
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player -> {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}));
		context.waitFor(minecraft -> minecraft.level != null && minecraft.level.dimension() == dimension, 400);
		world.getConnection().waitForChunksRender();
		context.waitTicks(10);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
