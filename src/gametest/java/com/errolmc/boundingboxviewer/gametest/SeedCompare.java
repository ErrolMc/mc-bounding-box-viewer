package com.errolmc.boundingboxviewer.gametest;

import com.errolmc.boundingboxviewer.client.seed.SeedDimension;
import com.errolmc.boundingboxviewer.client.seed.SeedMode;
import com.errolmc.boundingboxviewer.client.seed.SeedSnapping;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.Box;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

/**
 * Seed mode next to the server: what seed mode works out for a structure against what the server generated. Planning
 * must be exact; the pieces that settle onto the ground when their chunk is decorated must be exact but for their
 * height, which is measured.
 */
final class SeedCompare {
	/** The vanilla structures whose pieces (all of them) settle onto the ground when their chunk is decorated. */
	static final Set<String> SETTLING = Set.of("minecraft:swamp_hut", "minecraft:jungle_pyramid", "minecraft:desert_pyramid", "minecraft:igloo",
		"minecraft:shipwreck", "minecraft:shipwreck_beached", "minecraft:ocean_ruin_cold", "minecraft:ocean_ruin_warm", "minecraft:buried_treasure");

	private SeedCompare() {
	}

	/**
	 * How close seed mode's settled heights must be to the server's for a structure: no piece more than {@code worst}
	 * blocks off, and (over 20 pieces or more) at least {@code exactPercent} of them exact. Seed mode reads the ground as
	 * the noise makes it; caves cut through it, and trees, lakes and other structures on it, move some pieces further.
	 * Measured over the planning test's worlds (start chunk decorated first), with room to spare: swamp huts, jungle
	 * temples and igloos exact; desert temples, shipwrecks and ocean ruins 80 to 95% exact, a few far off (up to 22);
	 * buried treasure within 2 nearly always, its depth under the sand being random.
	 */
	record Limit(int worst, int exactPercent) {
	}

	static final Map<String, Limit> LIMITS = Map.of(
		"minecraft:swamp_hut", new Limit(4, 90),
		"minecraft:jungle_pyramid", new Limit(4, 90),
		"minecraft:igloo", new Limit(4, 90),
		"minecraft:desert_pyramid", new Limit(12, 80),
		"minecraft:shipwreck", new Limit(24, 70),
		"minecraft:shipwreck_beached", new Limit(12, 80),
		"minecraft:ocean_ruin_cold", new Limit(32, 85),
		"minecraft:ocean_ruin_warm", new Limit(32, 85),
		"minecraft:buried_treasure", new Limit(4, 15));

	/** How far off in height seed mode may put a settled piece of the structure. */
	static int maxDy(String id) {
		Limit limit = LIMITS.get(id);
		return limit != null ? limit.worst() : 0;
	}

	/**
	 * A structure on the server, and for each piece whether a chunk it's in is decorated now (so it has settled, if it
	 * settles).
	 */
	record ServerStart(StructureInfo info, List<Boolean> decorated) {
		String at() {
			return info.id() + " starting in " + ChunkPos.unpack(info.startChunk());
		}
	}

	/** Server thread. */
	static ServerStart serverStart(ServerLevel level, String id, Structure structure, StructureStart start) {
		List<Boolean> decorated = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			decorated.add(decorated(level, piece.getBoundingBox()));
		}
		return new ServerStart(TestWorlds.serverInfo(level, id, structure, start), decorated);
	}

	/** True when a chunk the box is in is loaded and decorated. Server thread. */
	static boolean decorated(ServerLevel level, BoundingBox box) {
		for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
			for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
				ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.EMPTY, false);
				if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Seed mode's structure starting in a chunk: as planned, and as shown (settled). */
	record SeedStart(StructureInfo planned, StructureInfo settled) {
	}

	/** Seed mode's plan for the structure starting in the chunk, or null when it plans none there. */
	static SeedStart seedStart(SeedDimension dimension, String id, long chunk) {
		for (SeedDimension.Planned planned : dimension.plan(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
			if (planned.id().equals(id)) {
				return new SeedStart(dimension.plannedInfo(planned), dimension.info(planned));
			}
		}
		return null;
	}

	/**
	 * What's wrong with seed mode's version of a structure the server has, or null when nothing is. {@code planned} is
	 * seed mode's plan and {@code shown} what it shows: the same for structures that don't settle, which must be the
	 * server's exactly. For those that do, every piece must be the server's moved up or down, and by at most
	 * {@link #maxDy} once it has settled on the server (decorated, or moved from where it was planned); those heights
	 * go into the stats. A piece that hasn't settled on the server yet must be where seed mode planned it.
	 */
	static String difference(ServerStart server, StructureInfo planned, StructureInfo shown, Stats stats) {
		StructureInfo truth = server.info();
		String at = server.at();
		if (planned == null || shown == null) {
			return "seed mode has no " + at;
		}
		if (!shown.id().equals(truth.id()) || shown.startChunk() != truth.startChunk()) {
			return at + " is " + shown.id() + " starting in " + ChunkPos.unpack(shown.startChunk()) + " in seed mode";
		}
		if (shown.kind() != truth.kind() || shown.spawnArea() != truth.spawnArea()) {
			return at + " is a " + shown.kind() + " with spawn area " + shown.spawnArea() + " in seed mode, a " + truth.kind() + " with "
				+ truth.spawnArea() + " on the server";
		}
		int count = truth.pieces().size();
		if (shown.pieces().size() != count || planned.pieces().size() != count) {
			return at + " has " + shown.pieces().size() + " pieces in seed mode, " + count + " on the server";
		}
		if (!SETTLING.contains(truth.id())) {
			if (shown.equals(truth)) {
				return null;
			}
			for (int i = 0; i < count; i++) {
				if (!shown.pieces().get(i).equals(truth.pieces().get(i))) {
					return at + ": piece " + i + " is " + shown.pieces().get(i) + " in seed mode, " + truth.pieces().get(i) + " on the server";
				}
			}
			return at + " box " + shown.bounds() + " in seed mode, " + truth.bounds() + " on the server";
		}
		List<Integer> heights = new ArrayList<>();
		int worst = 0;
		for (int i = 0; i < count; i++) {
			Box real = truth.pieces().get(i);
			Box seen = shown.pieces().get(i);
			Box plan = planned.pieces().get(i);
			if (!sameFootprint(real, seen) || !sameFootprint(real, plan)) {
				return at + ": piece " + i + " is " + seen + " in seed mode (planned " + plan + "), not the server's " + real + " moved up or down";
			}
			boolean settled = server.decorated().get(i) || !real.equals(plan);
			if (settled) {
				int dy = seen.minY() - real.minY();
				heights.add(dy);
				worst = Math.max(worst, Math.abs(dy));
			}
		}
		// Pieces moving by different amounts can make the whole box taller or shorter, but never wider.
		if (!sameColumns(truth.bounds(), shown.bounds())) {
			return at + " box " + shown.bounds() + " in seed mode isn't over the server's " + truth.bounds();
		}
		stats.add(truth.id(), heights);
		if (worst > maxDy(truth.id())) {
			return at + " is " + worst + " blocks off in height: " + shown.pieces() + " in seed mode, " + truth.pieces() + " on the server";
		}
		return null;
	}

	/** The settling structures {@link SeedSnapping} puts on their blocks: all but buried treasure (on its chest instead). */
	static boolean snaps(String id) {
		return SETTLING.contains(id) && !id.equals("minecraft:buried_treasure");
	}

	/** The blocks under the structure's pieces as the client has them, or null when it lacks a chunk of them. Client thread. */
	static SeedSnapping.Columns clientBlocks(ClientLevel level, StructureInfo info) {
		for (ChunkPos chunk : SeedSnapping.chunks(info)) {
			if (SeedMode.clientChunk(level, chunk.x(), chunk.z()) == null) {
				return null;
			}
		}
		return SeedSnapping.read(info, level, (x, z) -> SeedMode.clientChunk(level, x, z));
	}

	/** The commonest blocks in the box, with how many, to say what's there when something isn't found. */
	static String blocksIn(BlockGetter level, Box box) {
		Map<Block, Integer> counts = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
			counts.merge(level.getBlockState(pos).getBlock(), 1, Integer::sum);
		}
		return counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(8)
			.map(entry -> BuiltInRegistries.BLOCK.getKey(entry.getKey()).getPath() + " " + entry.getValue()).collect(Collectors.joining(", "));
	}

	/** The blocks under the structure's pieces on the server, or null when a chunk of them isn't decorated. Server thread. */
	static SeedSnapping.Columns serverBlocks(ServerLevel level, StructureInfo info) {
		for (ChunkPos chunk : SeedSnapping.chunks(info)) {
			ChunkAccess access = level.getChunk(chunk.x(), chunk.z(), ChunkStatus.EMPTY, false);
			if (access == null || !access.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
				return null;
			}
		}
		return SeedSnapping.read(info, level, (x, z) -> level.getChunk(x, z, ChunkStatus.EMPTY, false));
	}

	/**
	 * What's wrong with seed mode's structure put on its blocks (all of its chunks decorated), or null when nothing is:
	 * each piece found must be where the server has it or where its blocks are in one of its chunks (see
	 * {@link #onBlocks}). How many were found, and exact, go into the stats.
	 *
	 * @param shown what was put on the blocks
	 * @param blocks the blocks it was put on
	 */
	static String snapDifference(SeedDimension dimension, ServerStart server, StructureInfo shown, SeedSnapping.Snapped snapped,
			SeedSnapping.Columns blocks, Snaps snaps) {
		StructureInfo truth = server.info();
		if (!snaps(truth.id())) {
			return null;
		}
		List<Box> real = truth.pieces();
		List<Box> mine = snapped.info().pieces();
		if (mine.size() != real.size()) {
			return server.at() + " has " + mine.size() + " pieces put on its blocks, " + real.size() + " on the server";
		}
		OnBlocks on = onBlocks(dimension, shown, mine, snapped.found(), real, blocks);
		snaps.add(server, real.size(), on.found(), on.exact(), on.elsewhere());
		return on.wrong().isEmpty() ? null : server.at() + " put on its blocks: " + String.join(", ", on.wrong());
	}

	/**
	 * Pieces put on their blocks against the server's: of those found, how many are where the server has them, how many
	 * where their blocks are in one of their chunks but not the server's box, and what's wrong with the rest.
	 */
	record OnBlocks(int found, int exact, int elsewhere, List<String> wrong) {
	}

	/**
	 * Each piece found ({@code found} null: every one) must be where the server has it, or where its blocks are in one
	 * of its chunks ({@link #chunkHeights}): the game places a piece a chunk at a time, and can work its height out
	 * again for a later chunk (an ocean ruin always does; a shipwreck or a temple when its start chunk was read back
	 * from disk in between, which a dedicated server does with chunks it lets go of), so its blocks aren't always all
	 * at one height. The server's box is from the last time; seed mode's is where most of the blocks are.
	 *
	 * @param shown what was put on the blocks, to put each chunk's on them alone
	 */
	static OnBlocks onBlocks(SeedDimension dimension, StructureInfo shown, List<Box> mine, List<Boolean> found, List<Box> real,
			SeedSnapping.Columns blocks) {
		int count = 0;
		int exact = 0;
		int elsewhere = 0;
		List<String> wrong = new ArrayList<>();
		List<Set<Integer>> heights = null;
		for (int i = 0; i < real.size(); i++) {
			if (found != null && !found.get(i)) {
				continue;
			}
			count++;
			Box at = mine.get(i);
			Box truth = real.get(i);
			if (at.equals(truth)) {
				exact++;
				continue;
			}
			if (heights == null) {
				heights = chunkHeights(dimension, shown, blocks);
			}
			if (sameFootprint(at, truth) && heights.get(i).contains(at.minY())) {
				elsewhere++;
			} else {
				wrong.add("piece " + i + " at " + at + ", not the server's " + truth + " (its blocks chunk by chunk at y " + heights.get(i) + ")");
			}
		}
		return new OnBlocks(count, exact, elsewhere, wrong);
	}

	/**
	 * For each piece, the heights it's found at on each of its chunks' blocks alone. A chunk can have only a row of a
	 * piece, so any number of its blocks will do, as long as half of those there match.
	 */
	static List<Set<Integer>> chunkHeights(SeedDimension dimension, StructureInfo shown, SeedSnapping.Columns blocks) {
		List<Set<Integer>> heights = new ArrayList<>();
		shown.pieces().forEach(piece -> heights.add(new TreeSet<>()));
		for (ChunkPos chunk : SeedSnapping.chunks(shown)) {
			SeedSnapping.Snapped one = SeedSnapping.snap(dimension, shown, blocks.only(chunk.x(), chunk.z()), 1);
			for (int i = 0; i < heights.size(); i++) {
				if (one.found().get(i)) {
					heights.get(i).add(one.info().pieces().get(i).minY());
				}
			}
		}
		return heights;
	}

	/**
	 * Snapping per structure: pieces tried on their blocks, found, found where the server has them, and found where their
	 * blocks are in another of their chunks.
	 */
	static final class Snaps {
		private record Result(int pieces, int found, int exact, int elsewhere) {
		}

		private final String name;
		/** The latest for each structure (id and start chunk), as one is often tried at several places. */
		private final Map<String, Map<Long, Result>> results = new TreeMap<>();
		private final Map<String, List<String>> notFound = new TreeMap<>();

		Snaps(String name) {
			this.name = name;
		}

		synchronized void add(ServerStart server, int pieces, int found, int exact, int elsewhere) {
			String id = server.info().id();
			results.computeIfAbsent(id, key -> new LinkedHashMap<>()).put(server.info().startChunk(), new Result(pieces, found, exact, elsewhere));
			if (found < pieces) {
				List<String> list = notFound.computeIfAbsent(id, key -> new ArrayList<>());
				String at = ChunkPos.unpack(server.info().startChunk()) + " (" + (pieces - found) + " of " + pieces + " at " + server.info().bounds() + ")";
				if (list.size() < 8 && !list.contains(at)) {
					list.add(at);
				}
			}
		}

		synchronized void addAll(Snaps other) {
			other.results.forEach((id, byChunk) -> results.computeIfAbsent(id, key -> new LinkedHashMap<>()).putAll(byChunk));
			other.notFound.forEach((id, list) -> notFound.computeIfAbsent(id, key -> new ArrayList<>()).addAll(list));
		}

		synchronized int structures() {
			return results.values().stream().mapToInt(Map::size).sum();
		}


		synchronized void log(Logger logger) {
			logger.info("Put on their blocks ({}): {} structures", name, structures());
			results.forEach((id, byChunk) -> {
				int pieces = 0;
				int found = 0;
				int exact = 0;
				int elsewhere = 0;
				for (Result result : byChunk.values()) {
					pieces += result.pieces();
					found += result.found();
					exact += result.exact();
					elsewhere += result.elsewhere();
				}
				logger.info("  {}: {} structures, {} pieces, found {}%, exact {}% of those found (the rest where their blocks are in another chunk: {}); "
					+ "not found at {}", id, byChunk.size(), pieces, pieces == 0 ? 0 : 100 * found / pieces, found == 0 ? 0 : 100 * exact / found,
					elsewhere, notFound.getOrDefault(id, List.of()));
			});
		}
	}

	/** The same box but for where it is up or down. */
	static boolean sameFootprint(Box a, Box b) {
		return sameColumns(a, b) && a.ySpan() == b.ySpan();
	}

	/** Over the same columns, whatever the heights. */
	static boolean sameColumns(Box a, Box b) {
		return a.minX() == b.minX() && a.maxX() == b.maxX() && a.minZ() == b.minZ() && a.maxZ() == b.maxZ();
	}

	/** Seed mode's height minus the server's, of each settled piece, per structure. */
	static final class Stats {
		private final String name;
		private final Map<String, List<Integer>> heights = new TreeMap<>();

		Stats(String name) {
			this.name = name;
		}

		synchronized void add(String id, List<Integer> dys) {
			if (!dys.isEmpty()) {
				heights.computeIfAbsent(id, key -> new ArrayList<>()).addAll(dys);
			}
		}

		synchronized void addAll(Stats other) {
			other.heights.forEach(this::add);
		}

		synchronized int count() {
			return heights.values().stream().mapToInt(List::size).sum();
		}

		/** The structures with enough pieces measured whose share exact is under their {@link Limit}. */
		synchronized List<String> tooFewExact() {
			List<String> problems = new ArrayList<>();
			heights.forEach((id, dys) -> {
				Limit limit = LIMITS.get(id);
				long exact = dys.stream().filter(dy -> dy == 0).count();
				if (limit != null && dys.size() >= 20 && exact * 100 < (long) limit.exactPercent() * dys.size()) {
					problems.add(id + " " + (exact * 100 / dys.size()) + "% exact, under " + limit.exactPercent() + "%");
				}
			});
			return problems;
		}

		synchronized void log(Logger logger) {
			logger.info("Settled heights, seed mode minus server ({}): {} pieces", name, count());
			heights.forEach((id, dys) -> {
				int exact = 0;
				int within1 = 0;
				int within2 = 0;
				int within4 = 0;
				long sum = 0;
				long sumAbs = 0;
				int max = 0;
				Map<Integer, Integer> histogram = new TreeMap<>();
				for (int dy : dys) {
					int abs = Math.abs(dy);
					exact += abs == 0 ? 1 : 0;
					within1 += abs <= 1 ? 1 : 0;
					within2 += abs <= 2 ? 1 : 0;
					within4 += abs <= 4 ? 1 : 0;
					sum += dy;
					sumAbs += abs;
					max = Math.max(max, abs);
					histogram.merge(dy, 1, Integer::sum);
				}
				int n = dys.size();
				logger.info("  {}: {} pieces, exact {}%, within 1 {}%, within 2 {}%, within 4 {}%, mean {}, mean off {}, worst {}; {}", id, n,
					100 * exact / n, 100 * within1 / n, 100 * within2 / n, 100 * within4 / n, String.format("%.2f", (double) sum / n),
					String.format("%.2f", (double) sumAbs / n), max, histogram);
			});
		}
	}
}
