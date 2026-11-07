package com.errolmc.boundingboxviewer.gametest;

import com.errolmc.boundingboxviewer.client.seed.SeedDimension;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
