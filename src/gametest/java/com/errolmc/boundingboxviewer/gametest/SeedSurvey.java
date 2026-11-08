package com.errolmc.boundingboxviewer.gametest;

import com.errolmc.boundingboxviewer.client.seed.SeedSnapping;
import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.Box;
import com.errolmc.boundingboxviewer.structure.WorldType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The seed survey ({@code gradlew seedSurvey}, set up in build.gradle): {@link SeedPlanningClientGameTest} on many
 * seeds, several game clients at once, the other tests skipped. Each client (instance) writes what it found to
 * {@code seed-survey/current}: every piece of every settling structure compared ({@code instance-N-pieces.csv}),
 * each world ({@code instance-N-worlds.csv}), and what was wrong ({@code instance-N-problems.txt}).
 * {@link SeedSurveySummary} then sums them up.
 *
 * <p>Set by system properties: {@code bbv.survey}, {@code bbv.survey.instance} and {@code .instances}, {@code .seeds}
 * (how many random seeds, over all instances) or {@code .seedList} (these seeds, shared out), {@code .type},
 * {@code .perStructure}, {@code .dir}.
 */
final class SeedSurvey implements AutoCloseable {
	/** True when this game client runs the survey. */
	static final boolean ACTIVE = Boolean.getBoolean("bbv.survey");

	static final String PIECES_HEADER = "seed,type,dimension,structure,start_x,start_z,piece,min_x,min_z,max_x,max_z,height,planned_y,seed_y,server_y,dy,"
		+ "settled,biome,snap_y";
	static final String WORLDS_HEADER = "seed,type,seconds,settled_pieces,result";

	private final int instance;
	private final List<Long> seeds;
	private final WorldType type;
	private final int perStructure;
	private final BufferedWriter pieces;
	private final BufferedWriter worlds;
	private final BufferedWriter problems;

	private SeedSurvey(int instance, List<Long> seeds, WorldType type, int perStructure, Path dir) throws IOException {
		this.instance = instance;
		this.seeds = List.copyOf(seeds);
		this.type = type;
		this.perStructure = perStructure;
		Files.createDirectories(dir);
		pieces = Files.newBufferedWriter(dir.resolve("instance-" + instance + "-pieces.csv"), StandardCharsets.UTF_8);
		worlds = Files.newBufferedWriter(dir.resolve("instance-" + instance + "-worlds.csv"), StandardCharsets.UTF_8);
		problems = Files.newBufferedWriter(dir.resolve("instance-" + instance + "-problems.txt"), StandardCharsets.UTF_8);
		line(pieces, PIECES_HEADER);
		line(worlds, WORLDS_HEADER);
	}

	/** This instance's share of the survey, from the system properties; its files are started afresh. */
	static SeedSurvey open() {
		int instance = Integer.getInteger("bbv.survey.instance", 0);
		int instances = Math.max(1, Integer.getInteger("bbv.survey.instances", 1));
		String typeKey = System.getProperty("bbv.survey.type", "normal");
		WorldType type = WorldType.byKey(typeKey);
		if (type == null) {
			throw new IllegalArgumentException("unknown world type " + typeKey + " (normal, large_biomes or amplified)");
		}
		List<Long> seeds = new ArrayList<>();
		String list = System.getProperty("bbv.survey.seedList", "").trim();
		if (!list.isEmpty()) {
			String[] given = list.split(",");
			for (int i = instance; i < given.length; i += instances) {
				seeds.add(Long.parseLong(given[i].trim()));
			}
		} else {
			// Random seeds, as a new world gets: different every survey, and written down with what was found.
			int total = Integer.getInteger("bbv.survey.seeds", 100);
			int mine = total / instances + (instance < total % instances ? 1 : 0);
			SecureRandom random = new SecureRandom();
			for (int i = 0; i < mine; i++) {
				seeds.add(random.nextLong());
			}
		}
		Path dir = Path.of(System.getProperty("bbv.survey.dir", "seed-survey")).resolve("current");
		try {
			return new SeedSurvey(instance, seeds, type, Math.max(1, Integer.getInteger("bbv.survey.perStructure", 20)), dir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Places to locate more settling structures from in a world, beyond the planning test's own: 24 spread over a disc 4,000
	 * to 32,000 blocks out (away from spawn, which is decorated), the same for the same seed.
	 */
	static List<BlockPos> moreLocateFrom(long seed) {
		Random random = new Random(seed ^ 0x5DEECE66DL);
		List<BlockPos> from = new ArrayList<>();
		for (int i = 0; i < 24; i++) {
			double angle = random.nextDouble() * 2 * Math.PI;
			double distance = 4000 + random.nextDouble() * 28000;
			from.add(new BlockPos((int) (Math.cos(angle) * distance), 64, (int) (Math.sin(angle) * distance)));
		}
		return from;
	}

	int instance() {
		return instance;
	}

	List<Long> seeds() {
		return seeds;
	}

	WorldType type() {
		return type;
	}

	int perStructure() {
		return perStructure;
	}

	/**
	 * Writes down each piece of a settling structure: where the server put it, where seed mode planned and shows it, and
	 * whether it has settled on the server (decorated, or moved). Nothing when the pieces don't match up (that's a
	 * problem of its own).
	 */
	void structure(SeedPlanningClientGameTest.World world, ResourceKey<Level> dimension, SeedCompare.ServerStart real, SeedCompare.SeedStart mine,
			SeedSnapping.Snapped snapped, List<String> biomes) {
		StructureInfo truth = real.info();
		List<Box> server = truth.pieces();
		List<Box> shown = mine.settled().pieces();
		List<Box> planned = mine.planned().pieces();
		if (shown.size() != server.size() || planned.size() != server.size()) {
			return;
		}
		ChunkPos start = ChunkPos.unpack(truth.startChunk());
		for (int i = 0; i < server.size(); i++) {
			Box piece = server.get(i);
			Box seen = shown.get(i);
			Box plan = planned.get(i);
			if (!SeedCompare.sameFootprint(piece, seen) || !SeedCompare.sameFootprint(piece, plan)) {
				return;
			}
			boolean settled = real.decorated().get(i) || !piece.equals(plan);
			line(pieces, world.seed() + "," + world.type().key() + "," + dimension.identifier() + "," + truth.id() + "," + start.x() + "," + start.z() + ","
				+ i + "," + piece.minX() + "," + piece.minZ() + "," + piece.maxX() + "," + piece.maxZ() + "," + piece.ySpan() + "," + plan.minY() + ","
				+ seen.minY() + "," + piece.minY() + "," + (seen.minY() - piece.minY()) + "," + (settled ? 1 : 0) + "," + biomes.get(i) + ","
				+ (snapped != null && snapped.found().get(i) ? Integer.toString(snapped.info().pieces().get(i).minY()) : ""));
		}
	}

	/** Writes down a world surveyed: how long it took, how many settled pieces, how it went, and what was wrong. */
	void world(SeedPlanningClientGameTest.World world, long seconds, int settledPieces, String result, List<String> wrong) {
		line(worlds, world.seed() + "," + world.type().key() + "," + seconds + "," + settledPieces + "," + result);
		for (String problem : wrong) {
			line(problems, "seed " + world.seed() + " (" + world.type().key() + "): " + problem.replaceAll("\\s*\\R\\s*", " "));
		}
		flush();
	}

	private void flush() {
		try {
			pieces.flush();
			worlds.flush();
			problems.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void close() {
		try {
			pieces.close();
			worlds.close();
			problems.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void line(BufferedWriter writer, String line) {
		try {
			writer.write(line);
			writer.newLine();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
