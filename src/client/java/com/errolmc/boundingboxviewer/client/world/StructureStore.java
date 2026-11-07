package com.errolmc.boundingboxviewer.client.world;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Every structure the singleplayer server has loaded since the world opened, by dimension. Written on the server
 * thread by {@link StructureCollector}, read on the client thread by the overlay; cleared when the server stops.
 */
public final class StructureStore {
	/** A structure is named by its id and the chunk it starts in. */
	record StartKey(String id, long chunk) {
	}

	private static final Map<ResourceKey<Level>, Map<StartKey, StructureInfo>> LEVELS = new ConcurrentHashMap<>();

	private StructureStore() {
	}

	static boolean contains(ResourceKey<Level> level, String id, long chunk) {
		Map<StartKey, StructureInfo> structures = LEVELS.get(level);
		return structures != null && structures.containsKey(new StartKey(id, chunk));
	}

	/** Adds the structure, or replaces what was read of it before. */
	static void put(ResourceKey<Level> level, StructureInfo info) {
		LEVELS.computeIfAbsent(level, key -> new ConcurrentHashMap<>()).put(new StartKey(info.id(), info.startChunk()), info);
	}

	/** A live view: it may grow while it is being read. */
	public static Collection<StructureInfo> in(ResourceKey<Level> level) {
		Map<StartKey, StructureInfo> structures = LEVELS.get(level);
		return structures != null ? structures.values() : List.of();
	}

	/** The structure with this id starting in this chunk, or null when the server hasn't loaded it. */
	public static StructureInfo get(ResourceKey<Level> level, String id, long chunk) {
		Map<StartKey, StructureInfo> structures = LEVELS.get(level);
		return structures != null ? structures.get(new StartKey(id, chunk)) : null;
	}

	static void clear() {
		LEVELS.clear();
	}
}

