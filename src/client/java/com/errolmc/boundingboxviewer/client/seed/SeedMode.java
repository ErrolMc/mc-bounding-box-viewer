package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.client.config.BoxConfig;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureStore;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.ServerKey;
import com.errolmc.boundingboxviewer.structure.WorldType;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structure boxes on servers, worked out from the world's seed. On a server other than this game's own (multiplayer,
 * Realms, a LAN world someone else hosts), the server never says where structures are, so with the boxes on, worker
 * threads plan every chunk around the player the way the server did when it generated them, and put what starts there
 * in {@link StructureStore} for the overlay.
 *
 * <p>The seed is saved per server ({@link ServerKey}): picked up from {@code /seed}'s reply, or given with
 * {@code /boxes seed}. Planning is exact; the pieces that settle onto the ground when their chunk is decorated are put
 * on the terrain the seed makes (see {@link SeedSettling}), and buried treasure on its chest once its chunk is here.
 */
public final class SeedMode {
	public enum Status {
		/** Singleplayer, or not in a world: seed mode isn't used. */
		OFF,
		/** On a server whose seed isn't known. */
		NO_SEED,
		/** Setting up the seed's world. */
		LOADING,
		READY,
		/** Setting up the seed's world failed; the log says why. */
		FAILED
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer");
	private static final int THREADS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
	/** Chunks handed to the workers at a time, nearest first, so moving on doesn't leave a long queue behind. */
	private static final int MAX_IN_FLIGHT = 128;
	/** Once all chunks in range are done, how often to look again (more often when the player crosses into a new chunk). */
	private static final int SCAN_INTERVAL_TICKS = 10;
	/**
	 * Chunks a structure can reach beyond the one it starts in: as far as the game records references to it. So the
	 * structures in range are those starting within the range plus this.
	 */
	static final int REACH_CHUNKS = 8;
	private static final String BURIED_TREASURE = "minecraft:buried_treasure";

	/** A chunk handed to the workers, for one seed. */
	private record Task(int epoch, ResourceKey<Level> level, long chunk) {
	}

	/** Guards {@link #epoch} changes against workers adding to the store for a seed that's gone. */
	private static final Object LOCK = new Object();
	private static ExecutorService pool;
	/** Counts seed changes; workers drop what they found for an older one. */
	private static volatile int epoch;
	private static volatile SeedWorld world;
	private static volatile Status status = Status.OFF;
	private static volatile BoxConfig.SavedSeed current;
	/** The server's key while connected to one, null in singleplayer. Client thread. */
	private static String serverKey;

	private static final Map<ResourceKey<Level>, Set<Long>> DONE = new ConcurrentHashMap<>();
	private static final Set<Task> QUEUED = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
	/** Buried treasure whose chest has been found, so its box is where it really is. */
	private static final Set<Task> TREASURES_FOUND = ConcurrentHashMap.newKeySet();
	private static volatile boolean changed;
	private static volatile boolean caughtUp;
	private static int ticksUntilScan;
	private static ChunkPos lastCenter;
	private static ResourceKey<Level> lastLevel;

	private SeedMode() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((listener, sender, minecraft) -> onJoin(minecraft));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, minecraft) -> onLeave());
		ClientTickEvents.END_CLIENT_TICK.register(SeedMode::tick);
	}

	/** True on a server other than this game's own, where boxes come from the seed. */
	public static boolean isActive() {
		return serverKey != null;
	}

	public static boolean hasSeed() {
		return serverKey != null && current != null;
	}

	public static Status status() {
		return status;
	}

	/** The seed in use, or null. */
	public static BoxConfig.SavedSeed current() {
		return current;
	}

	/** The world the seed makes, once set up; null before. */
	public static SeedWorld world() {
		return world;
	}

	/** The key the server's seed is saved under, or null in singleplayer. */
	public static String serverKey() {
		return serverKey;
	}

	/**
	 * True when every chunk in range has been worked out and nothing is left to do (for the tests and the toggle's
	 * message).
	 */
	public static boolean idle() {
		return status == Status.READY && caughtUp && IN_FLIGHT.get() == 0;
	}

	/**
	 * Uses this seed for the server from now on, and saves it. Returns false in singleplayer. The same seed again
	 * changes nothing; another starts over.
	 */
	public static boolean setSeed(long seed, WorldType type) {
		if (serverKey == null) {
			return false;
		}
		BoxConfig.SavedSeed saved = new BoxConfig.SavedSeed(seed, type);
		BoxConfig.get().seeds.put(serverKey, saved);
		BoxConfig.save();
		BoxConfig.SavedSeed was = current;
		if (was != null && was.seed == seed && was.type() == type && status != Status.FAILED) {
			return true;
		}
		start(saved);
		return true;
	}

	/** Forgets the server's seed and turns the boxes off. Returns false in singleplayer. */
	public static boolean clearSeed() {
		if (serverKey == null) {
			return false;
		}
		BoxConfig.get().seeds.remove(serverKey);
		BoxConfig.save();
		start(null);
		BoxOverlay.setEnabled(false);
		return true;
	}

	private static void onJoin(Minecraft minecraft) {
		if (minecraft.hasSingleplayerServer()) {
			// This game's own server: StructureCollector reads it, and the store is already filling.
			serverKey = null;
			stop(false);
			status = Status.OFF;
			return;
		}
		serverKey = keyOf(minecraft);
		LOGGER.info("Joined {}: seed {}", serverKey, BoxConfig.get().seeds.containsKey(serverKey) ? "saved" : "not known");
		start(BoxConfig.get().seeds.get(serverKey));
	}

	private static void onLeave() {
		if (serverKey == null) {
			return;
		}
		serverKey = null;
		stop(true);
		status = Status.OFF;
	}

	static String keyOf(Minecraft minecraft) {
		ServerData data = minecraft.getCurrentServer();
		if (data != null) {
			return ServerKey.of(data.isRealm(), data.name, data.ip);
		}
		ClientPacketListener listener = minecraft.getConnection();
		SocketAddress address = listener != null ? listener.getConnection().getRemoteAddress() : null;
		return ServerKey.of(false, null, address != null ? address.toString() : "unknown");
	}

	/** Drops everything for the old seed; with {@code clearStore}, the structures found with it too. */
	private static void stop(boolean clearStore) {
		synchronized (LOCK) {
			epoch++;
			if (clearStore) {
				StructureStore.clear();
			}
			DONE.clear();
		}
		TREASURES_FOUND.clear();
		world = null;
		current = null;
		caughtUp = false;
		lastCenter = null;
		changed = true;
	}

	private static void start(BoxConfig.SavedSeed saved) {
		stop(true);
		current = saved;
		if (saved == null) {
			status = Status.NO_SEED;
			return;
		}
		status = Status.LOADING;
		int taskEpoch = epoch;
		long seed = saved.seed;
		WorldType type = saved.type();
		pool().execute(() -> {
			try {
				long started = System.nanoTime();
				SeedWorld created = SeedWorld.create(seed, type);
				// Strongholds go on rings worked out once for the world: get that done before planning chunks.
				SeedDimension overworld = created.dimension(Level.OVERWORLD);
				if (overworld != null) {
					overworld.structureState().ensureStructuresGenerated();
				}
				LOGGER.info("Seed mode ready for seed {} ({}) in {} ms", seed, type.key(), (System.nanoTime() - started) / 1_000_000);
				if (epoch == taskEpoch) {
					world = created;
					status = Status.READY;
				}
			} catch (RuntimeException | LinkageError e) {
				LOGGER.error("Could not set up seed mode for seed {}", seed, e);
				if (epoch == taskEpoch) {
					status = Status.FAILED;
				}
			}
		});
	}

	private static synchronized ExecutorService pool() {
		if (pool == null) {
			AtomicInteger count = new AtomicInteger();
			pool = Executors.newFixedThreadPool(THREADS, runnable -> {
				Thread thread = new Thread(runnable, "Bounding Box Viewer seed " + count.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
		}
		return pool;
	}

	private static void tick(Minecraft minecraft) {
		if (serverKey == null) {
			return;
		}
		if (changed) {
			changed = false;
			BoxOverlay.rebuildSoon();
		}
		SeedWorld seedWorld = world;
		ClientLevel clientLevel = minecraft.level;
		if (seedWorld == null || !BoxOverlay.isEnabled() || clientLevel == null || minecraft.player == null) {
			caughtUp = false;
			return;
		}
		ResourceKey<Level> level = clientLevel.dimension();
		ChunkPos center = minecraft.player.chunkPosition();
		boolean moved = !center.equals(lastCenter) || level != lastLevel;
		if (caughtUp && !moved && --ticksUntilScan > 0) {
			return;
		}
		ticksUntilScan = SCAN_INTERVAL_TICKS;
		lastCenter = center;
		lastLevel = level;
		findTreasure(clientLevel, level);
		schedule(seedWorld, level, center, Mth.ceil(BoxConfig.get().range / 16.0) + REACH_CHUNKS);
	}

	/** Hands the workers the chunks within the radius not done yet, ring by ring from the middle. */
	private static void schedule(SeedWorld seedWorld, ResourceKey<Level> level, ChunkPos center, int radius) {
		int taskEpoch = epoch;
		Set<Long> done = DONE.computeIfAbsent(level, key -> ConcurrentHashMap.newKeySet());
		boolean left = false;
		boolean submitted = false;
		rings:
		for (int ring = 0; ring <= radius; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					long chunk = ChunkPos.pack(center.x() + dx, center.z() + dz);
					Task task = new Task(taskEpoch, level, chunk);
					if (done.contains(chunk) || QUEUED.contains(task)) {
						continue;
					}
					if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) {
						left = true;
						break rings;
					}
					QUEUED.add(task);
					IN_FLIGHT.incrementAndGet();
					submitted = true;
					pool().execute(() -> run(seedWorld, task));
				}
			}
		}
		caughtUp = !left && !submitted && IN_FLIGHT.get() == 0;
	}

	private static void run(SeedWorld seedWorld, Task task) {
		try {
			if (epoch != task.epoch()) {
				return;
			}
			SeedDimension dimension = seedWorld.dimension(task.level());
			List<StructureInfo> infos = dimension != null
				? dimension.structuresStartingIn(ChunkPos.getX(task.chunk()), ChunkPos.getZ(task.chunk()))
				: List.of();
			synchronized (LOCK) {
				if (epoch != task.epoch()) {
					return;
				}
				for (StructureInfo info : infos) {
					StructureStore.put(task.level(), info);
				}
				DONE.computeIfAbsent(task.level(), key -> ConcurrentHashMap.newKeySet()).add(task.chunk());
			}
			if (!infos.isEmpty()) {
				changed = true;
			}
		} catch (RuntimeException e) {
			LOGGER.error("Could not work out the structures in chunk {} of {}", ChunkPos.unpack(task.chunk()), task.level().identifier(), e);
			synchronized (LOCK) {
				// Not again and again.
				if (epoch == task.epoch()) {
					DONE.computeIfAbsent(task.level(), key -> ConcurrentHashMap.newKeySet()).add(task.chunk());
				}
			}
		} finally {
			QUEUED.remove(task);
			IN_FLIGHT.decrementAndGet();
		}
	}

	/**
	 * Buried treasure is a chest dug into the stone under the sand, which the terrain alone doesn't show: once its
	 * chunk is here, its box goes on the chest.
	 */
	private static void findTreasure(ClientLevel clientLevel, ResourceKey<Level> level) {
		int taskEpoch = epoch;
		for (StructureInfo info : StructureStore.in(level)) {
			if (!info.id().equals(BURIED_TREASURE)) {
				continue;
			}
			Task key = new Task(taskEpoch, level, info.startChunk());
			Box box = info.bounds();
			int chunkX = box.minX() >> 4;
			int chunkZ = box.minZ() >> 4;
			if (TREASURES_FOUND.contains(key) || !clientLevel.hasChunk(chunkX, chunkZ)) {
				continue;
			}
			LevelChunk chunk = clientLevel.getChunk(chunkX, chunkZ);
			int top = Math.min(clientLevel.getMaxY(), box.minY() + 16);
			int bottom = Math.max(clientLevel.getMinY(), box.minY() - 32);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(box.minX(), top, box.minZ());
			for (int y = top; y >= bottom; y--) {
				if (chunk.getBlockState(pos.setY(y)).is(Blocks.CHEST)) {
					Box chest = new Box(box.minX(), y, box.minZ(), box.minX(), y, box.minZ());
					synchronized (LOCK) {
						if (epoch == taskEpoch) {
							StructureStore.put(level, new StructureInfo(info.id(), info.kind(), info.startChunk(), chest, List.of(chest), info.spawnArea()));
							TREASURES_FOUND.add(key);
							changed = true;
						}
					}
					break;
				}
			}
		}
	}
}
