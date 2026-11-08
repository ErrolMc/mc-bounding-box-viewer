package com.errolmc.boundingboxviewer.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sums up the seed survey's data ({@code gradlew seedSurveySummary}, run after every survey too). A survey's files,
 * in {@code current/}, first go into a folder named for when it finished, with that survey's {@code summary.txt}; then
 * {@code summary.txt} and {@code all-pieces.csv} at the top cover every survey kept. A seed surveyed twice counts once
 * (the later survey's). Plain Java, no game: it only reads and writes text.
 *
 * <p>Per settling structure: how often seed mode's height is the server's exactly, within 1, 2 and 4, the mean and
 * worst, the spread, the worst cases (with where to look), and the biomes it does worst in. Then, for surveys with
 * {@code snap_y}, how often a piece was found on its blocks, and how often those were where the server has them.
 */
public final class SeedSurveySummary {
	private static final int WORST_SHOWN = 6;
	private static final int BIOME_MIN = 10;
	/** The world a problem line is about, as {@link SeedSurvey} starts them: {@code seed <seed> (<type>): }. */
	private static final Pattern PROBLEM_WORLD = Pattern.compile("seed (\\S+) \\((\\S+)\\): ");

	private record Piece(String seed, String type, String dimension, String structure, int startX, int startZ, int piece, int minX, int minZ, int maxX,
			int maxZ, int serverY, int dy, boolean settled, String biome, boolean snapTried, Integer snapDy) {
		String key() {
			return seed + "|" + type + "|" + dimension + "|" + structure + "|" + startX + "|" + startZ + "|" + piece;
		}

		String structureKey() {
			return seed + "|" + type + "|" + dimension + "|" + structure + "|" + startX + "|" + startZ;
		}

		/** The same for pieces laid in one place and landing the same: a cold ocean ruin's brick, cracked and mossy layers. */
		String placeKey() {
			return structureKey() + "|" + minX + "|" + minZ + "|" + maxX + "|" + maxZ + "|" + serverY + "|" + dy;
		}

		String where() {
			return ((minX + maxX) / 2) + " " + serverY + " " + ((minZ + maxZ) / 2) + " in " + dimension + " (seed " + seed + ", " + type + ", " + biome + ")";
		}
	}

	private record World(int seconds, int pieces, String result) {
	}

	private SeedSurveySummary() {
	}

	public static void main(String[] args) throws IOException {
		Path dir = Path.of(args.length > 0 ? args[0] : "seed-survey");
		Files.createDirectories(dir);
		Path current = dir.resolve("current");
		if (Files.isDirectory(current) && hasData(current)) {
			Path survey = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
			Files.move(current, survey);
			String text = summary(List.of(survey), null);
			Files.writeString(survey.resolve("summary.txt"), text, StandardCharsets.UTF_8);
			System.out.println("This survey (" + survey + "):");
			System.out.println(text);
		}
		List<Path> surveys;
		try (Stream<Path> list = Files.list(dir)) {
			surveys = list.filter(Files::isDirectory).filter(path -> !path.getFileName().toString().equals("current")).sorted().toList();
		}
		String text = summary(surveys, dir.resolve("all-pieces.csv"));
		Files.writeString(dir.resolve("summary.txt"), text, StandardCharsets.UTF_8);
		System.out.println("All " + surveys.size() + " surveys in " + dir + ":");
		System.out.println(text);
	}

	private static boolean hasData(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.anyMatch(file -> file.getFileName().toString().endsWith(".csv"));
		}
	}

	/** The summary of these surveys' data (later ones win for a seed in two); the merged pieces to {@code merged} too. */
	static String summary(List<Path> surveys, Path merged) throws IOException {
		Map<String, Piece> pieces = new LinkedHashMap<>();
		Map<String, World> worlds = new LinkedHashMap<>();
		boolean snapped = false;
		// By world (seed|type), so a world surveyed again has only the later survey's problems.
		Map<String, Set<String>> problems = new LinkedHashMap<>();
		for (Path survey : surveys) {
			for (Path file : files(survey, "-pieces.csv")) {
				for (String[] row : rows(file)) {
					// snap_y: only in later surveys, and empty for a piece not found on its blocks.
					boolean snapTried = row.length > 18;
					Integer snapDy = snapTried && !row[18].isBlank() ? parse(row[18]) - parse(row[14]) : null;
					Piece piece = new Piece(row[0], row[1], row[2], row[3], parse(row[4]), parse(row[5]), parse(row[6]), parse(row[7]), parse(row[8]),
						parse(row[9]), parse(row[10]), parse(row[14]), parse(row[15]), row[16].equals("1"), row[17], snapTried, snapDy);
					snapped |= snapTried;
					pieces.put(piece.key(), piece);
				}
			}
			for (Path file : files(survey, "-worlds.csv")) {
				for (String[] row : rows(file)) {
					worlds.put(row[0] + "|" + row[1], new World(parse(row[2]), parse(row[3]), row[4]));
					problems.remove(row[0] + "|" + row[1]);
				}
			}
			for (Path file : files(survey, "-problems.txt")) {
				Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank())
					.forEach(line -> problems.computeIfAbsent(worldOf(line), key -> new LinkedHashSet<>()).add(line));
			}
		}
		if (merged != null) {
			List<String> lines = new ArrayList<>();
			lines.add(SeedSurvey.PIECES_HEADER);
			for (Path survey : surveys) {
				for (Path file : files(survey, "-pieces.csv")) {
					List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
					lines.addAll(all.subList(Math.min(1, all.size()), all.size()));
				}
			}
			Files.write(merged, dedupe(lines), StandardCharsets.UTF_8);
		}

		StringBuilder out = new StringBuilder();
		Map<String, Integer> results = new TreeMap<>();
		worlds.values().forEach(world -> results.merge(world.result(), 1, Integer::sum));
		double seconds = worlds.values().stream().mapToInt(World::seconds).average().orElse(0);
		Map<String, Piece> places = new LinkedHashMap<>();
		pieces.values().stream().filter(Piece::settled).forEach(piece -> places.putIfAbsent(piece.placeKey(), piece));
		Map<String, List<Piece>> byStructure = new TreeMap<>();
		places.values().forEach(piece -> byStructure.computeIfAbsent(piece.structure(), key -> new ArrayList<>()).add(piece));
		int settled = byStructure.values().stream().mapToInt(List::size).sum();
		out.append(worlds.size()).append(" worlds ").append(results).append(", ").append(settled).append(" settled pieces; ")
			.append(String.format(Locale.ROOT, "%.0f", seconds)).append(" s a world per game client\n");
		out.append("Height off = seed mode's minus the server's, in blocks. Pieces laid in one place that land the same (a cold ocean ruin's three\n")
			.append("layers) count once.\n");
		for (Map.Entry<String, List<Piece>> entry : byStructure.entrySet()) {
			out.append('\n').append(structure(entry.getKey(), entry.getValue()));
		}
		if (snapped) {
			out.append("\nPut on their blocks (as seed mode does once the client has the chunks): found = share of pieces found on their\n")
				.append("blocks; exact = share of those found where the server has them.\n");
			for (Map.Entry<String, List<Piece>> entry : byStructure.entrySet()) {
				out.append(snaps(entry.getKey(), entry.getValue()));
			}
		}
		List<String> listed = problems.values().stream().flatMap(Set::stream).toList();
		out.append('\n').append(listed.size()).append(listed.size() == 1 ? " problem" : " problems").append(listed.isEmpty() ? "\n" : ":\n");
		listed.forEach(problem -> out.append("  ").append(problem).append('\n'));
		return out.toString();
	}

	/** The world (seed|type) a problem line is about; the line itself for one that doesn't say. */
	private static String worldOf(String problem) {
		Matcher matcher = PROBLEM_WORLD.matcher(problem);
		return matcher.lookingAt() ? matcher.group(1) + "|" + matcher.group(2) : problem;
	}

	private static String structure(String id, List<Piece> pieces) {
		int n = pieces.size();
		long structures = pieces.stream().map(Piece::structureKey).distinct().count();
		long worlds = pieces.stream().map(piece -> piece.seed() + "|" + piece.type()).distinct().count();
		Map<Integer, Integer> histogram = new TreeMap<>();
		long sum = 0;
		long sumOff = 0;
		int worst = 0;
		for (Piece piece : pieces) {
			histogram.merge(piece.dy(), 1, Integer::sum);
			sum += piece.dy();
			sumOff += Math.abs(piece.dy());
			worst = Math.max(worst, Math.abs(piece.dy()));
		}
		StringBuilder out = new StringBuilder();
		out.append(id).append(": ").append(n).append(" pieces (").append(structures).append(structures == 1 ? " structure, " : " structures, ")
			.append(worlds).append(worlds == 1 ? " world); exact " : " worlds); exact ")
			.append(percent(within(pieces, 0), n)).append(", within 1 ").append(percent(within(pieces, 1), n)).append(", within 2 ")
			.append(percent(within(pieces, 2), n)).append(", within 4 ").append(percent(within(pieces, 4), n))
			.append(String.format(Locale.ROOT, "; mean %+.2f, mean off %.2f, worst %d%n", (double) sum / n, (double) sumOff / n, worst));
		StringBuilder spread = new StringBuilder();
		histogram.forEach((dy, count) -> spread.append(spread.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "%+d", dy)).append(" x").append(count));
		out.append("  off by: ").append(spread).append('\n');
		List<Piece> worstPieces = pieces.stream().filter(piece -> piece.dy() != 0)
			.sorted(Comparator.comparingInt((Piece piece) -> -Math.abs(piece.dy()))).limit(WORST_SHOWN).toList();
		for (Piece piece : worstPieces) {
			out.append(String.format(Locale.ROOT, "  %+d at ", piece.dy())).append(piece.where()).append('\n');
		}
		Map<String, List<Piece>> byBiome = new TreeMap<>();
		pieces.forEach(piece -> byBiome.computeIfAbsent(piece.biome(), key -> new ArrayList<>()).add(piece));
		List<Map.Entry<String, List<Piece>>> biomes = byBiome.entrySet().stream().filter(entry -> entry.getValue().size() >= BIOME_MIN)
			.sorted(Comparator.comparingDouble(entry -> (double) within(entry.getValue(), 0) / entry.getValue().size())).toList();
		if (byBiome.size() > 1 && !biomes.isEmpty()) {
			out.append("  by biome (").append(BIOME_MIN).append("+ pieces, worst first): ");
			for (int i = 0; i < biomes.size(); i++) {
				List<Piece> in = biomes.get(i).getValue();
				out.append(i == 0 ? "" : ", ").append(biomes.get(i).getKey()).append(' ').append(percent(within(in, 0), in.size())).append(" exact of ")
					.append(in.size());
			}
			out.append('\n');
		}
		return out.toString();
	}

	private static String snaps(String id, List<Piece> all) {
		// Only pieces from surveys that tried (older ones have no snap_y).
		List<Piece> pieces = all.stream().filter(Piece::snapTried).toList();
		if (id.equals("minecraft:buried_treasure") || pieces.isEmpty()) {
			return "";
		}
		int n = pieces.size();
		List<Piece> found = pieces.stream().filter(piece -> piece.snapDy() != null).toList();
		long exact = found.stream().filter(piece -> piece.snapDy() == 0).count();
		StringBuilder out = new StringBuilder();
		out.append("  ").append(id).append(": ").append(n).append(" pieces, found ").append(percent(found.size(), n)).append(", exact ")
			.append(percent(exact, found.size())).append(" of those found");
		List<Piece> off = found.stream().filter(piece -> piece.snapDy() != 0).sorted(Comparator.comparingInt((Piece piece) -> -Math.abs(piece.snapDy())))
			.limit(WORST_SHOWN).toList();
		if (!off.isEmpty()) {
			out.append("; off:");
			off.forEach(piece -> out.append(String.format(Locale.ROOT, " %+d at ", piece.snapDy())).append(piece.where()).append(';'));
		}
		List<Piece> missed = pieces.stream().filter(piece -> piece.snapDy() == null).limit(3).toList();
		if (!missed.isEmpty()) {
			out.append(" not found, for example:");
			missed.forEach(piece -> out.append(' ').append(piece.where()).append(';'));
		}
		return out.append('\n').toString();
	}

	private static long within(List<Piece> pieces, int blocks) {
		return pieces.stream().filter(piece -> Math.abs(piece.dy()) <= blocks).count();
	}

	private static String percent(long count, int of) {
		return of == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * count / of);
	}

	private static List<Path> files(Path survey, String suffix) throws IOException {
		try (Stream<Path> files = Files.list(survey)) {
			return files.filter(file -> file.getFileName().toString().endsWith(suffix)).sorted().toList();
		}
	}

	/** The rows after the header, split at commas (nothing written has a comma in it). */
	private static List<String[]> rows(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		List<String[]> rows = new ArrayList<>();
		for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
			if (!line.isBlank()) {
				rows.add(line.split(",", -1));
			}
		}
		return rows;
	}

	/** The merged rows, the later of any two for the same piece kept (in the later one's place). */
	private static List<String> dedupe(List<String> lines) {
		Map<String, String> byPiece = new LinkedHashMap<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] row = line.split(",", -1);
			if (row.length < 7) {
				continue;
			}
			String key = String.join("|", row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
			byPiece.remove(key);
			byPiece.put(key, line);
		}
		List<String> out = new ArrayList<>();
		out.add(lines.getFirst());
		out.addAll(byPiece.values());
		return out;
	}

	private static int parse(String text) {
		return Integer.parseInt(text.trim());
	}
}
