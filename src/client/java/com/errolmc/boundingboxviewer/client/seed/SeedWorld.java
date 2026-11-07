package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.structure.WorldType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** A world as its seed and world type make it: its dimensions, each set up when first asked for. */
public final class SeedWorld {
	private final VanillaWorldgen worldgen;
	private final long seed;
	private final WorldType type;
	private final Map<ResourceKey<LevelStem>, LevelStem> stems;
	private final Map<ResourceKey<Level>, SeedDimension> dimensions = new ConcurrentHashMap<>();

	private SeedWorld(VanillaWorldgen worldgen, long seed, WorldType type, Map<ResourceKey<LevelStem>, LevelStem> stems) {
		this.worldgen = worldgen;
		this.seed = seed;
		this.type = type;
		this.stems = stems;
	}

	/** Loads vanilla's world generation the first time, which takes a second or two. */
	public static SeedWorld create(long seed, WorldType type) {
		VanillaWorldgen worldgen = VanillaWorldgen.get();
		WorldPreset preset = worldgen.registries().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset(type)).value();
		return new SeedWorld(worldgen, seed, type, preset.createWorldDimensions().dimensions());
	}

	private static ResourceKey<WorldPreset> preset(WorldType type) {
		return switch (type) {
			case NORMAL -> WorldPresets.NORMAL;
			case LARGE_BIOMES -> WorldPresets.LARGE_BIOMES;
			case AMPLIFIED -> WorldPresets.AMPLIFIED;
		};
	}

	public long seed() {
		return seed;
	}

	public WorldType type() {
		return type;
	}

	/** The dimension, or null for one the world type doesn't have (a datapack's own). */
	public SeedDimension dimension(ResourceKey<Level> level) {
		return dimensions.computeIfAbsent(level, key -> {
			LevelStem stem = stems.get(Registries.levelToLevelStem(key));
			return stem != null ? new SeedDimension(worldgen, seed, key, stem) : null;
		});
	}
}
