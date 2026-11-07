package com.errolmc.boundingboxviewer.client.world;

import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.SpawnArea;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads structures from the singleplayer (integrated) server's chunks as they load, on the server thread.
 *
 * <p>A structure is saved in the chunk it starts in; every chunk it reaches into keeps a reference to that chunk. So a
 * loaded chunk gives its own starts at once, and its references name starts in chunks that may not be loaded: those
 * are queued and read a few per tick from the chunk's structure-starts stage, which the game keeps on disk (vanilla
 * does the same when it checks whether a spot is in a fortress).
 *
 * <p>Some pieces move after the structure is planned: swamp huts, temples, igloos, shipwrecks and ocean ruins are
 * planned at a guessed height and settle onto the ground when the chunk they're in is decorated. So when a newly
 * generated chunk loads, the structures it starts or refers to are read again.
 */
public final class StructureCollector {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer");
	/** Chunks read per server tick for references; each may come from disk, so a few at a time keep ticks short. */
	static final int LOADS_PER_TICK = 8;

	private record Pending(ResourceKey<Level> level, Structure structure, String id, long chunk) {
		StartRef key() {
			return new StartRef(level, id, chunk);
		}
	}

	private record StartRef(ResourceKey<Level> level, String id, long chunk) {
	}

	private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
	/** Starts in the queue, so each is in it once at a time. */
	private static final Set<StartRef> QUEUED = ConcurrentHashMap.newKeySet();
	/** Starts looked for at least once, so a reference to a start that isn't there isn't followed again and again. */
	private static final Set<StartRef> SEEN = ConcurrentHashMap.newKeySet();

	private StructureCollector() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_LOAD.register(StructureCollector::onChunkLoad);
		ServerTickEvents.END_SERVER_TICK.register(StructureCollector::readPending);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> reset());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
	}

	/** Starts still waiting to be read, for the tests. */
	public static int pending() {
		return PENDING.size();
	}

	private static void reset() {
		PENDING.clear();
		QUEUED.clear();
		SEEN.clear();
		StructureStore.clear();
	}

	private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
		ResourceKey<Level> dimension = level.dimension();
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
			record(dimension, registry, entry.getKey(), entry.getValue());
		}
		long self = chunk.getPos().pack();
		for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
			Identifier key = registry.getKey(entry.getKey());
			if (key == null) {
				continue;
			}
			String id = key.toString();
			for (LongIterator it = entry.getValue().iterator(); it.hasNext(); ) {
				long start = it.nextLong();
				if (start == self) {
					continue;
				}
				StartRef ref = new StartRef(dimension, id, start);
				// Known: only again when this chunk was just decorated, which may have moved some of its pieces.
				boolean read = StructureStore.contains(dimension, id, start) ? generated : SEEN.add(ref);
				if (read && QUEUED.add(ref)) {
					PENDING.add(new Pending(dimension, entry.getKey(), id, start));
				}
			}
		}
	}

	private static void readPending(MinecraftServer server) {
		int loads = 0;
		while (loads < LOADS_PER_TICK) {
			Pending pending = PENDING.poll();
			if (pending == null) {
				return;
			}
			QUEUED.remove(pending.key());
			ServerLevel level = server.getLevel(pending.level());
			if (level == null) {
				continue;
			}
			loads++;
			ChunkAccess chunk = level.getChunkSource().getChunk(ChunkPos.getX(pending.chunk()), ChunkPos.getZ(pending.chunk()),
				ChunkStatus.STRUCTURE_STARTS, true);
			StructureStart start = chunk != null ? chunk.getStartForStructure(pending.structure()) : null;
			if (start != null) {
				record(pending.level(), level.registryAccess().lookupOrThrow(Registries.STRUCTURE), pending.structure(), start);
			} else {
				LOGGER.debug("No {} starts in chunk {} although a chunk refers to it", pending.id(), ChunkPos.unpack(pending.chunk()));
			}
		}
	}

	private static void record(ResourceKey<Level> dimension, Registry<Structure> registry, Structure structure, StructureStart start) {
		if (!start.isValid()) {
			return;
		}
		Identifier key = registry.getKey(structure);
		if (key == null) {
			return;
		}
		String id = key.toString();
		List<StructurePiece> pieces = start.getPieces();
		List<BoundingBox> pieceBoxes = new ArrayList<>(pieces.size());
		List<Box> boxes = new ArrayList<>(pieces.size());
		for (StructurePiece piece : pieces) {
			pieceBoxes.add(piece.getBoundingBox());
			boxes.add(box(piece.getBoundingBox()));
		}
		StructureStore.put(dimension, new StructureInfo(id, StructureKind.of(id), start.getChunkPos().pack(),
			box(bounds(structure, pieceBoxes)), boxes, spawnArea(structure)));
	}

	/**
	 * The structure's box as the game works it out: around all its pieces, 12 blocks bigger for structures that reshape
	 * the ground around them (villages, outposts, strongholds, ancient cities, trail ruins, trial chambers). Not
	 * {@code StructureStart.getBoundingBox()}: that is worked out once, before pieces settle onto the ground, and kept
	 * until the world is loaded again, so a swamp hut's would be at the height it was planned at.
	 */
	static BoundingBox bounds(Structure structure, List<BoundingBox> pieces) {
		return structure.adjustBoundingBox(BoundingBox.encapsulatingBoxes(pieces).orElseThrow());
	}

	static Box box(BoundingBox box) {
		return new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
	}

	/** Where the structure's own mobs spawn: overrides without mobs only stop other spawns, so they don't count. */
	static SpawnArea spawnArea(Structure structure) {
		SpawnArea area = SpawnArea.NONE;
		for (StructureSpawnOverride override : structure.spawnOverrides().values()) {
			if (override.spawns().isEmpty()) {
				continue;
			}
			if (override.boundingBox() == StructureSpawnOverride.BoundingBoxType.STRUCTURE) {
				return SpawnArea.WHOLE;
			}
			area = SpawnArea.PIECES;
		}
		return area;
	}
}

