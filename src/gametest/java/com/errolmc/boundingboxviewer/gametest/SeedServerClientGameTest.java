package com.errolmc.boundingboxviewer.gametest;

import static com.errolmc.boundingboxviewer.gametest.TestWorlds.check;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.seed.SeedDimension;
import com.errolmc.boundingboxviewer.client.seed.SeedMode;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureStore;
import com.errolmc.boundingboxviewer.structure.WorldType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed mode as it's used: on a dedicated server, which sends the game no structures. The client joins with no seed (B
 * says so), picks the seed up from {@code /seed} once it's an operator, turns the boxes on and, at spawn and at a dozen
 * structures across the three dimensions, everything the server starts within 12 chunks must be in seed mode's boxes,
 * and nothing else: structures that don't settle exactly the server's, those that do the server's moved up or down a
 * little (buried treasure exactly, once its chunk is here). Then: rejoining uses the saved seed; a wrong seed shows
 * other structures; {@code /boxes seed} sets the world type and text seeds; {@code /boxes seed clear} forgets it.
 */
public class SeedServerClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer-gametest");
	private static final long SEED = 8478637045098429971L;
	/** Chunks around the player whose structures are compared: inside the 16 that seed mode works out at the default range. */
	private static final int CHECK_RADIUS = 12;
	private static final int ATTEMPTS = 15;
	/** Ticks to wait for seed mode to work out everything in range. */
	private static final int IDLE_TIMEOUT = 20 * 120;
	private static final String BURIED_TREASURE = "minecraft:buried_treasure";

	private record Target(ResourceKey<Level> dimension, String path) {
	}

	private static final List<Target> TARGETS = List.of(
		new Target(Level.OVERWORLD, "buried_treasure"),
		new Target(Level.OVERWORLD, "swamp_hut"),
		new Target(Level.OVERWORLD, "shipwreck"),
		new Target(Level.OVERWORLD, "shipwreck_beached"),
		new Target(Level.OVERWORLD, "desert_pyramid"),
		new Target(Level.OVERWORLD, "jungle_pyramid"),
		new Target(Level.OVERWORLD, "igloo"),
		new Target(Level.OVERWORLD, "ocean_ruin_warm"),
		new Target(Level.OVERWORLD, "ocean_ruin_cold"),
		new Target(Level.OVERWORLD, "village_plains"),
		new Target(Level.OVERWORLD, "monument"),
		new Target(Level.NETHER, "fortress"),
		new Target(Level.NETHER, "bastion_remnant"),
		new Target(Level.END, "end_city"));

	private final SeedCompare.Stats stats = new SeedCompare.Stats("on a server, seed mode's boxes as the player arrives");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (SeedSurvey.ACTIVE) {
			return;
		}
		context.runOnClient(MuteAudio::mute);
		TestWorlds.resetConfig(context);
		try (TestDedicatedServerContext server = TestWorlds.builder(context, SEED, WorldType.NORMAL).createServer()) {
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			String key;
			StructureInfo village = null;
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				// Spectator: a camera inside the rock (the Nether) sees through it rather than the in-a-wall overlay.
				server.runCommand("gamemode spectator @a");
				key = context.computeOnClient(minecraft -> SeedMode.serverKey());
				check(key != null && key.startsWith("server:"), "seed mode not on for a server: " + key);
				check(context.computeOnClient(minecraft -> SeedMode.status() == SeedMode.Status.NO_SEED && SeedMode.current() == null),
					"a seed before one was given");
				TestWorlds.pressToggle(context);
				check(!context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "B turned the boxes on with no seed");

				pickUpSeed(context, server, connection, key);
				TestWorlds.pressToggle(context);
				check(context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "B did not turn the boxes on with the seed");
				compareAround(context, server, Level.OVERWORLD, "spawn");

				for (Target target : TARGETS) {
					ResourceKey<Structure> structure = ResourceKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace(target.path()));
					BlockPos from = target.dimension() == Level.END ? new BlockPos(1500, 64, 0) : BlockPos.ZERO;
					StructureInfo located = TestWorlds.locate(server, target.dimension(), structure, from);
					TestWorlds.goOver(context, server, connection, target.dimension(), located.bounds());
					compareAround(context, server, target.dimension(), target.path());
					check(context.computeOnClient(minecraft -> {
						StructureInfo shown = StructureStore.get(target.dimension(), located.id(), located.startChunk());
						return shown != null && BoxOverlay.shown().contains(shown);
					}), target.path() + " isn't shown");
					context.takeScreenshot("seed-" + target.path());
					if (target.path().equals("village_plains")) {
						village = located;
					}
				}
			}
			check(context.computeOnClient(minecraft -> SeedMode.status() == SeedMode.Status.OFF && SeedMode.serverKey() == null),
				"seed mode still on after leaving the server");
			for (ResourceKey<Level> dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
				check(context.computeOnClient(minecraft -> StructureStore.in(dimension).isEmpty()), "structures kept after leaving: " + dimension.identifier());
			}

			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				check(key.equals(context.computeOnClient(minecraft -> SeedMode.serverKey())), "the server's key changed between joins");
				check(context.computeOnClient(minecraft -> SeedMode.current() != null && SeedMode.current().seed == SEED), "the saved seed wasn't used");
				if (!context.computeOnClient(minecraft -> BoxOverlay.isEnabled())) {
					TestWorlds.pressToggle(context);
				}
				ResourceKey<Level> here = context.computeOnClient(minecraft -> minecraft.level.dimension());
				compareAround(context, server, here, "rejoined");

				TestWorlds.goOver(context, server, connection, Level.OVERWORLD, village.bounds());
				compareAround(context, server, Level.OVERWORLD, "village again");
				wrongSeed(context, server);
				commands(context, key);
				command(context, "boxes seed " + SEED);
				compareAround(context, server, Level.OVERWORLD, "seed given again");

				command(context, "boxes seed clear");
				check(context.computeOnClient(minecraft -> SeedMode.current() == null && SeedMode.status() == SeedMode.Status.NO_SEED
					&& !BoxOverlay.isEnabled() && StructureStore.in(Level.OVERWORLD).isEmpty() && !BoxConfig.get().seeds.containsKey(key)),
					"/boxes seed clear didn't forget the seed, turn the boxes off and clear them");
				TestWorlds.pressToggle(context);
				check(!context.computeOnClient(minecraft -> BoxOverlay.isEnabled()), "B turned the boxes on after the seed was cleared");
			}
		}
		TestWorlds.resetConfig(context);
		stats.log(LOGGER);
	}

	/** Not an operator, {@code /seed} fails and nothing is picked up; other messages aren't either; as one, it is. */
	private static void pickUpSeed(ClientGameTestContext context, TestDedicatedServerContext server, TestDedicatedServerConnection connection, String key) {
		command(context, "seed");
		server.runCommand("tellraw @a \"Seed: [99]\"");
		context.waitTicks(20);
		check(context.computeOnClient(minecraft -> SeedMode.current() == null), "a seed from a message other than /seed's");

		server.runCommand("op @a");
		connection.waitForClientboundPackets();
		context.waitTicks(10);
		command(context, "seed");
		context.waitFor(minecraft -> SeedMode.current() != null, 200);
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == SEED && SeedMode.current().type() == WorldType.NORMAL),
			"picked up the wrong seed from /seed: " + context.computeOnClient(minecraft -> SeedMode.current().seed));
		check(context.computeOnClient(minecraft -> {
			BoxConfig.SavedSeed saved = BoxConfig.get().seeds.get(key);
			return saved != null && saved.seed == SEED && saved.type() == WorldType.NORMAL;
		}), "the seed wasn't saved for the server");
		context.waitFor(minecraft -> SeedMode.status() == SeedMode.Status.READY, IDLE_TIMEOUT);

		server.runCommand("tellraw @a \"Seed: [99]\"");
		context.waitTicks(10);
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == SEED), "a later message replaced the seed");
	}

	/** {@code /boxes seed} with a world type, a bad one, and a seed in words. */
	private static void commands(ClientGameTestContext context, String key) {
		command(context, "boxes seed " + SEED + " amplified");
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == SEED && SeedMode.current().type() == WorldType.AMPLIFIED
			&& BoxConfig.get().seeds.get(key).type() == WorldType.AMPLIFIED), "/boxes seed didn't set the world type");
		command(context, "boxes seed 5 flat");
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == SEED && SeedMode.current().type() == WorldType.AMPLIFIED),
			"an unknown world type changed the seed");
		command(context, "boxes seed glacier large_biomes");
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == "glacier".hashCode() && SeedMode.current().type() == WorldType.LARGE_BIOMES),
			"/boxes seed didn't take a seed in words as the game does");
		context.waitFor(minecraft -> SeedMode.status() == SeedMode.Status.READY, IDLE_TIMEOUT);
	}

	/** With another seed, most of what the server has isn't where seed mode puts things. */
	private void wrongSeed(ClientGameTestContext context, TestDedicatedServerContext server) {
		command(context, "boxes seed 1234");
		check(context.computeOnClient(minecraft -> SeedMode.current().seed == 1234), "/boxes seed didn't change the seed");
		waitIdle(context);
		Comparison comparison = compare(context, server, Level.OVERWORLD, new SeedCompare.Stats("wrong seed"));
		LOGGER.info("With the wrong seed: {} problems with {} structures on the server", comparison.problems().size(), comparison.count());
		check(comparison.count() > 5 && comparison.problems().size() > comparison.count() / 2,
			"a wrong seed matched the server: " + comparison.problems().size() + " problems with " + comparison.count() + " structures");
	}

	private static void command(ClientGameTestContext context, String command) {
		context.runOnClient(minecraft -> minecraft.getConnection().sendCommand(command));
		context.waitTicks(5);
	}

	private static void waitIdle(ClientGameTestContext context) {
		context.waitFor(minecraft -> SeedMode.idle(), IDLE_TIMEOUT);
		context.waitTicks(2);
	}

	/** Compares until seed mode and the server agree, retrying while chunks load and treasure is found. */
	private void compareAround(ClientGameTestContext context, TestDedicatedServerContext server, ResourceKey<Level> dimension, String where) {
		Comparison comparison = null;
		SeedCompare.Stats attempt = null;
		for (int i = 0; i < ATTEMPTS; i++) {
			if (i > 0) {
				context.waitTicks(20);
			}
			waitIdle(context);
			attempt = new SeedCompare.Stats(where);
			comparison = compare(context, server, dimension, attempt);
			if (comparison.problems().isEmpty()) {
				break;
			}
		}
		check(comparison.problems().isEmpty(), where + ": " + String.join("; ", comparison.problems()));
		stats.addAll(attempt);
		LOGGER.info("{} ({}): seed mode has the server's {} structures in the {} x {} chunks around the player", where, dimension.identifier(),
			comparison.count(), 2 * CHECK_RADIUS + 1, 2 * CHECK_RADIUS + 1);
	}

	private record Comparison(int count, List<String> problems) {
	}

	private record ServerSide(ChunkPos center, Map<String, SeedCompare.ServerStart> starts) {
	}

	/**
	 * Every structure the server starts within {@link #CHECK_RADIUS} chunks of the player, against seed mode's: seed
	 * mode must have each (see {@link SeedCompare#difference}) and nothing more. Buried treasure whose chunk is here
	 * must be exactly on its chest.
	 */
	private static Comparison compare(ClientGameTestContext context, TestDedicatedServerContext server, ResourceKey<Level> dimension,
			SeedCompare.Stats stats) {
		ServerSide side = server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			ChunkPos center = minecraftServer.getPlayerList().getPlayers().getFirst().chunkPosition();
			Map<String, SeedCompare.ServerStart> starts = new LinkedHashMap<>();
			for (int x = center.x() - CHECK_RADIUS; x <= center.x() + CHECK_RADIUS; x++) {
				for (int z = center.z() - CHECK_RADIUS; z <= center.z() + CHECK_RADIUS; z++) {
					level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, true).getAllStarts().forEach((structure, start) -> {
						if (start.isValid()) {
							String id = TestWorlds.id(registry, structure);
							starts.put(id + "@" + start.getChunkPos().pack(), SeedCompare.serverStart(level, id, structure, start));
						}
					});
				}
			}
			return new ServerSide(center, starts);
		});
		ChunkPos center = side.center();
		Map<String, StructureInfo> shown = new LinkedHashMap<>();
		Set<String> treasureHere = new HashSet<>();
		context.runOnClient(minecraft -> {
			for (StructureInfo info : StructureStore.in(dimension)) {
				if (Math.abs(ChunkPos.getX(info.startChunk()) - center.x()) <= CHECK_RADIUS && Math.abs(ChunkPos.getZ(info.startChunk()) - center.z()) <= CHECK_RADIUS) {
					String key = info.id() + "@" + info.startChunk();
					shown.put(key, info);
					if (info.id().equals(BURIED_TREASURE) && minecraft.level.hasChunk(info.bounds().minX() >> 4, info.bounds().minZ() >> 4)) {
						treasureHere.add(key);
					}
				}
			}
		});
		SeedDimension seed = SeedMode.world().dimension(dimension);
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, SeedCompare.ServerStart> entry : side.starts().entrySet()) {
			SeedCompare.ServerStart start = entry.getValue();
			StructureInfo mine = shown.get(entry.getKey());
			SeedCompare.SeedStart planned = SeedCompare.seedStart(seed, start.info().id(), start.info().startChunk());
			String problem = SeedCompare.difference(start, planned == null ? null : planned.planned(), mine, stats);
			if (problem == null && treasureHere.contains(entry.getKey()) && start.decorated().getFirst() && !mine.equals(start.info())) {
				problem = start.at() + " is at " + mine.bounds() + " in seed mode, not on its chest at " + start.info().bounds();
			}
			if (problem != null) {
				problems.add(problem);
			}
		}
		for (Map.Entry<String, StructureInfo> entry : shown.entrySet()) {
			if (!side.starts().containsKey(entry.getKey())) {
				problems.add("seed mode shows " + entry.getValue().id() + " starting in " + ChunkPos.unpack(entry.getValue().startChunk())
					+ ", which the server doesn't have");
			}
		}
		return new Comparison(side.starts().size(), problems);
	}
}
