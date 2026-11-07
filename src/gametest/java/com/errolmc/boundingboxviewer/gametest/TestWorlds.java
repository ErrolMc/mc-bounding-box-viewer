package com.errolmc.boundingboxviewer.gametest;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.input.BoxKeys;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureInfos;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.WorldType;
import com.mojang.datafixers.util.Pair;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** What the client game tests share: making worlds, finding structures, moving about, the toggle and the config. */
final class TestWorlds {
	/** Locate's search radius, as {@code /locate} uses. */
	static final int LOCATE_RADIUS = 100;

	private TestWorlds() {
	}

	/** A world of the type and seed with structures (Fabric's test worlds are superflat without them otherwise). */
	static TestWorldBuilder builder(ClientGameTestContext context, long seed, WorldType type) {
		return context.worldBuilder().adjustSettings(settings -> {
			settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(settings.getSettings().worldgenLoadContext()
				.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset(type))));
			settings.setGenerateStructures(true);
			settings.setSeed(Long.toString(seed));
		});
	}

	static ResourceKey<WorldPreset> preset(WorldType type) {
		return switch (type) {
			case NORMAL -> WorldPresets.NORMAL;
			case LARGE_BIOMES -> WorldPresets.LARGE_BIOMES;
			case AMPLIFIED -> WorldPresets.AMPLIFIED;
		};
	}

	/**
	 * The settings back to their defaults and the boxes off: the test client reads the dev run's config. Seeds saved for
	 * the test servers (on this machine) are dropped, others kept.
	 */
	static void resetConfig(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			BoxConfig defaults = new BoxConfig();
			BoxConfig config = BoxConfig.get();
			config.range = defaults.range;
			config.showPieces = defaults.showPieces;
			config.showLabels = defaults.showLabels;
			config.shadeSpawnAreas = defaults.shadeSpawnAreas;
			config.seeThrough = defaults.seeThrough;
			config.hidden.clear();
			config.seeds.keySet().removeIf(key -> key.startsWith("server:localhost") || key.startsWith("server:127.0.0.1"));
			BoxOverlay.setEnabled(false);
		});
	}

	static void setConfig(ClientGameTestContext context, Consumer<BoxConfig> change) {
		context.runOnClient(minecraft -> {
			change.accept(BoxConfig.get());
			BoxOverlay.rebuildSoon();
		});
		context.waitTicks(2);
	}

	static void pressToggle(ClientGameTestContext context) {
		context.getInput().pressKey(BoxKeys.TOGGLE);
		context.waitTicks(10);
	}

	/** The structure as the server has it now: its pieces where they are and the box around them, as the game adjusts it. */
	static StructureInfo serverInfo(ServerLevel level, String id, Structure structure, StructureStart start) {
		return StructureInfos.of(id, structure, start.getChunkPos().pack(), StructureInfos.pieces(start, level.getStructureTemplateManager()));
	}

	static String id(Registry<Structure> registry, Structure structure) {
		return registry.getKey(structure).toString();
	}

	/** The nearest structure of the kind from a place, with the game's own locate code, as the server has it. */
	static StructureInfo locate(TestServerContext server, ResourceKey<Level> dimension, ResourceKey<Structure> key, BlockPos from) {
		StructureInfo found = tryLocate(server, dimension, key, from);
		if (found == null) {
			throw new AssertionError("locate found no " + key.identifier() + " from " + from);
		}
		return found;
	}

	/** {@link #locate}, or null when there's none within locate's radius. */
	static StructureInfo tryLocate(TestServerContext server, ResourceKey<Level> dimension, ResourceKey<Structure> key, BlockPos from) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			Holder<Structure> holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
			Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, HolderSet.direct(holder), from, LOCATE_RADIUS, false);
			if (nearest == null) {
				return null;
			}
			ChunkPos chunk = ChunkPos.containing(nearest.getFirst());
			StructureStart start = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.STRUCTURE_STARTS, true).getStartForStructure(holder.value());
			if (start == null || !start.isValid()) {
				throw new AssertionError("locate found " + key.identifier() + " in " + chunk + " but the chunk has no start");
			}
			return serverInfo(level, key.identifier().toString(), holder.value(), start);
		});
	}

	/**
	 * Goes over the near (north) side of a box, looking south across it and down: 12 above its top and, outside the
	 * Nether, at least 20 above the ground there, so structures underground are seen from the open air. The ground
	 * from the generator: the level's height map reads as the bottom of the world where nothing is loaded yet.
	 */
	static void goOver(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, ResourceKey<Level> dimension, Box bounds) {
		double x = bounds.centerX();
		double z = bounds.minZ() - 10;
		int y = server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			int overTop = bounds.top() + 12;
			if (level.dimensionType().hasCeiling()) {
				return overTop;
			}
			ServerChunkCache chunks = level.getChunkSource();
			int ground = chunks.getGenerator().getBaseHeight((int) Math.floor(x), (int) Math.floor(z), Heightmap.Types.MOTION_BLOCKING, level,
				chunks.randomState());
			return Math.max(overTop, ground + 20);
		});
		teleport(context, server, connection, dimension, x, y, z, 0, 35);
	}

	static void teleport(ClientGameTestContext context, TestServerContext server, TestServerConnection connection, ResourceKey<Level> dimension,
			double x, double y, double z, float yaw, float pitch) {
		server.runCommand("execute in " + dimension.identifier() + " run tp @a " + x + " " + y + " " + z + " " + yaw + " " + pitch);
		// Flying, so the view stays put for the screenshots.
		server.runOnServer(minecraftServer -> minecraftServer.getPlayerList().getPlayers().forEach(player -> {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}));
		context.waitFor(minecraft -> minecraft.level != null && minecraft.level.dimension() == dimension, 400);
		connection.waitForChunksRender();
		context.waitTicks(10);
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
