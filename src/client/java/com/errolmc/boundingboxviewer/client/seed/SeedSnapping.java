package com.errolmc.boundingboxviewer.client.seed;

import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.client.world.StructureInfos;
import com.errolmc.boundingboxviewer.structure.Box;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.structures.IglooPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settling pieces put where they really are, once the game has their chunks. The terrain alone puts them close (see
 * {@link SeedSettling}), but caves, lakes, icebergs and trees under them can move them further; their blocks don't lie.
 * Each piece is built as the game builds it, into a stand-in world that keeps the blocks ({@link Shape}), then slid up
 * and down over the blocks the game has ({@link Columns}) to where most of them are. Blocks that the game picks between
 * at random (mossy or cracked bricks, suspicious sand) count as the plain ones, and what ruin takes away (an ocean
 * ruin's missing blocks) only lowers the count. Buried treasure has its chest instead (see {@link SeedMode}).
 */
public final class SeedSnapping {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer");
	/** How far up or down from seed mode's estimate a piece is looked for: the estimate was never further off in the survey. */
	public static final int SEARCH = 64;
	/** How far under its box a piece builds (a desert pyramid's cellar: 14). */
	private static final int BELOW = 16;
	/** The least of a piece's blocks there must be, of those in chunks the game has, for it to count as found. */
	private static final double FOUND_SHARE = 0.5;
	private static final int FOUND_MIN = 8;
	private static final String[] VARIANT_PREFIXES = {"mossy_", "cracked_", "chiseled_", "suspicious_", "infested_"};
	private static final Map<Block, Block> PLAIN = new ConcurrentHashMap<>();
	/** Structures whose pieces couldn't be built, logged once each. */
	private static final Map<String, Boolean> FAILED = new ConcurrentHashMap<>();

	private SeedSnapping() {
	}

	/** The chunk at these chunk coordinates as the game has it, or null when it hasn't. */
	@FunctionalInterface
	public interface Chunks {
		ChunkAccess get(int chunkX, int chunkZ);
	}

	/**
	 * The blocks a piece places, relative to the corner of its box, the random ones (and air it clears) as the plain
	 * block: {@code x}, {@code y}, {@code z} and {@code blocks} line up.
	 */
	record Shape(int[] x, int[] y, int[] z, Block[] blocks) {
		int size() {
			return blocks.length;
		}
	}

	/**
	 * A piece's blocks, one {@link Shape} per palette of its template (a shipwreck has one per wood). The game picks the
	 * palette by where the template ends up, so which one is looked for depends on the height tried.
	 *
	 * @param templatePosition where the template is planned; null for pieces that aren't templates
	 * @param boxMinY the bottom of the piece's box as planned
	 */
	record Shapes(List<Shape> palettes, BlockPos templatePosition, int boxMinY) {
		/** The palette the game places with the bottom of the piece's box at {@code y}, as {@code StructurePlaceSettings} picks it. */
		int palette(int y) {
			if (palettes.size() == 1) {
				return 0;
			}
			BlockPos at = templatePosition.atY(templatePosition.getY() + y - boxMinY);
			return RandomSource.create(Mth.getSeed(at)).nextInt(palettes.size());
		}
	}

	/** The blocks of the columns under some boxes, as the game has them, plain ({@link #plain}); a column it hasn't is missing. */
	public static final class Columns {
		private final int minY;
		private final int height;
		private final Map<Long, Block[]> columns;

		private Columns(int minY, int height, Map<Long, Block[]> columns) {
			this.minY = minY;
			this.height = height;
			this.columns = columns;
		}

		Block[] column(int x, int z) {
			return columns.get(column(x, z, 0));
		}

		private static long column(int x, int z, int unused) {
			return (long) x << 32 | z & 0xFFFFFFFFL;
		}

		public boolean isEmpty() {
			return columns.isEmpty();
		}

		/** Only the columns in this chunk, as if the game had no others. */
		public Columns only(int chunkX, int chunkZ) {
			Map<Long, Block[]> in = new HashMap<>();
			columns.forEach((key, column) -> {
				if ((int) (key >> 32) >> 4 == chunkX && (int) (long) key >> 4 == chunkZ) {
					in.put(key, column);
				}
			});
			return new Columns(minY, height, in);
		}
	}

	/** A structure with its settling pieces put on their blocks where found ({@code found} per piece). */
	public record Snapped(StructureInfo info, List<Boolean> found) {
	}

	/** True for structures with pieces this puts on their blocks: those that settle, but buried treasure. */
	public static boolean snaps(SeedDimension.Planned planned) {
		for (StructurePiece piece : planned.start().getPieces()) {
			if (snaps(piece)) {
				return true;
			}
		}
		return false;
	}

	private static boolean snaps(StructurePiece piece) {
		return SeedSettling.settles(piece) && !(piece instanceof BuriedTreasurePieces.BuriedTreasurePiece);
	}

	/** The chunks the structure's pieces are over. */
	public static List<ChunkPos> chunks(StructureInfo info) {
		List<ChunkPos> chunks = new ArrayList<>();
		for (Box piece : info.pieces()) {
			for (int x = piece.minX() >> 4; x <= piece.maxX() >> 4; x++) {
				for (int z = piece.minZ() >> 4; z <= piece.maxZ() >> 4; z++) {
					ChunkPos chunk = new ChunkPos(x, z);
					if (!chunks.contains(chunk)) {
						chunks.add(chunk);
					}
				}
			}
		}
		return chunks;
	}

	/**
	 * Reads the columns under the structure's pieces, from far enough below to far enough above for {@link #snap}, from
	 * the chunks given. On the thread the chunks belong to.
	 */
	public static Columns read(StructureInfo info, LevelHeightAccessor height, Chunks chunks) {
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (Box piece : info.pieces()) {
			low = Math.min(low, piece.minY() - SEARCH - BELOW);
			high = Math.max(high, piece.maxY() + SEARCH);
		}
		int minY = Math.max(low, height.getMinY());
		int maxY = Math.min(high, height.getMaxY());
		Map<Long, Block[]> columns = new HashMap<>();
		Map<Long, ChunkAccess> loaded = new HashMap<>();
		for (Box piece : info.pieces()) {
			for (int x = piece.minX(); x <= piece.maxX(); x++) {
				for (int z = piece.minZ(); z <= piece.maxZ(); z++) {
					long key = Columns.column(x, z, 0);
					if (columns.containsKey(key)) {
						continue;
					}
					ChunkAccess chunk = loaded.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), packed -> chunks.get(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
					if (chunk != null && maxY >= minY) {
						columns.put(key, column(chunk, x, z, minY, maxY));
					}
				}
			}
		}
		return new Columns(minY, Math.max(0, maxY - minY + 1), columns);
	}

	private static Block[] column(ChunkAccess chunk, int x, int z, int minY, int maxY) {
		Block[] column = new Block[maxY - minY + 1];
		LevelChunkSection section = null;
		int sectionIndex = Integer.MIN_VALUE;
		for (int y = minY; y <= maxY; y++) {
			int index = chunk.getSectionIndex(y);
			if (index != sectionIndex) {
				sectionIndex = index;
				section = index >= 0 && index < chunk.getSections().length ? chunk.getSection(index) : null;
			}
			column[y - minY] = section == null || section.hasOnlyAir() ? Blocks.AIR
				: plain(section.getBlockState(x & 15, y & 15, z & 15));
		}
		return column;
	}

	/**
	 * Seed mode's structure with each settling piece moved up or down to where its blocks are, those not found (too few
	 * of their blocks in the chunks read) left where they were. {@code shown} is seed mode's structure as it is now.
	 */
	public static Snapped snap(SeedDimension dimension, StructureInfo shown, Columns blocks) {
		return snap(dimension, shown, blocks, FOUND_MIN);
	}

	/** As {@link #snap(SeedDimension, StructureInfo, Columns)}, a piece found with as few as {@code foundMin} of its blocks. */
	public static Snapped snap(SeedDimension dimension, StructureInfo shown, Columns blocks, int foundMin) {
		List<Boolean> found = new ArrayList<>();
		shown.pieces().forEach(piece -> found.add(false));
		if (blocks.isEmpty()) {
			return new Snapped(shown, found);
		}
		SeedDimension.Planned planned = null;
		for (SeedDimension.Planned start : dimension.plan(ChunkPos.getX(shown.startChunk()), ChunkPos.getZ(shown.startChunk()))) {
			if (start.id().equals(shown.id())) {
				planned = start;
			}
		}
		if (planned == null || planned.start().getPieces().size() != shown.pieces().size()) {
			return new Snapped(shown, found);
		}
		List<StructurePiece> pieces = planned.start().getPieces();
		// An igloo's pieces all move as its top does (the game moves each by the ground at its entrance), and the ladder
		// shaft's pieces look alike, so only the top is looked for.
		int iglooTop = -1;
		for (int i = 0; i < pieces.size(); i++) {
			if (pieces.get(i) instanceof IglooPieces.IglooPiece && (iglooTop < 0 || shown.pieces().get(i).maxY() > shown.pieces().get(iglooTop).maxY())) {
				iglooTop = i;
			}
		}
		Integer iglooMove = null;
		if (iglooTop >= 0) {
			Box top = shown.pieces().get(iglooTop);
			Shapes shapes = shapes(dimension, planned, pieces.get(iglooTop));
			Integer y = shapes != null ? find(shapes, blocks, top, foundMin) : null;
			iglooMove = y != null ? y - top.minY() : null;
		}
		List<BoundingBox> boxes = new ArrayList<>(pieces.size());
		for (int i = 0; i < pieces.size(); i++) {
			Box now = shown.pieces().get(i);
			BoundingBox box = new BoundingBox(now.minX(), now.minY(), now.minZ(), now.maxX(), now.maxY(), now.maxZ());
			StructurePiece piece = pieces.get(i);
			Integer move = null;
			if (piece instanceof IglooPieces.IglooPiece) {
				move = iglooMove;
			} else if (snaps(piece)) {
				Shapes shapes = shapes(dimension, planned, piece);
				Integer y = shapes != null ? find(shapes, blocks, now, foundMin) : null;
				move = y != null ? y - now.minY() : null;
			}
			if (move != null) {
				box = box.moved(0, move, 0);
				found.set(i, true);
			}
			boxes.add(box);
		}
		Structure structure = planned.structure().value();
		return new Snapped(StructureInfos.of(shown.id(), structure, shown.startChunk(), boxes), found);
	}

	/**
	 * Where the bottom of the piece's box is, now {@code at}, if its blocks are within {@link #SEARCH} of there; null when
	 * too few are. The game places a piece a chunk at a time and can work its height out again for each (an ocean ruin
	 * always does), so its blocks can be at one height in one chunk and another in the next: each chunk's part is fitted
	 * alone, and of the heights where a chunk's part is there, the one where most of the piece's blocks are wins, ties
	 * going to the nearest. Not the best fit of the whole piece, which with its parts at two heights can come out between
	 * them.
	 */
	static Integer find(Shapes shapes, Columns blocks, Box at, int foundMin) {
		int tries = 2 * SEARCH + 1;
		int palettes = shapes.palettes().size();
		Block[][][] byPalette = new Block[palettes][][];
		int[][] chunkByPalette = new int[palettes][];
		Map<Long, Integer> chunkIds = new HashMap<>();
		// Per chunk of the piece, per height tried (0, +1, -1, +2, -2...: the nearest first): matches less mismatches,
		// solid blocks matching, solid blocks there to read.
		List<int[][]> tallies = new ArrayList<>();
		for (int step = 0; step < tries; step++) {
			int y = tried(at, step);
			int palette = shapes.palette(y);
			Shape shape = shapes.palettes().get(palette);
			int n = shape.size();
			Block[][] columns = byPalette[palette];
			int[] chunkOf = chunkByPalette[palette];
			if (columns == null) {
				columns = new Block[n][];
				chunkOf = new int[n];
				for (int i = 0; i < n; i++) {
					int x = at.minX() + shape.x()[i];
					int z = at.minZ() + shape.z()[i];
					columns[i] = blocks.column(x, z);
					chunkOf[i] = chunkIds.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), key -> {
						tallies.add(new int[3][tries]);
						return tallies.size() - 1;
					});
				}
				byPalette[palette] = columns;
				chunkByPalette[palette] = chunkOf;
			}
			for (int i = 0; i < n; i++) {
				Block[] column = columns[i];
				if (column == null) {
					continue;
				}
				int index = y + shape.y()[i] - blocks.minY;
				if (index < 0 || index >= blocks.height) {
					continue;
				}
				int[][] tally = tallies.get(chunkOf[i]);
				Block want = shape.blocks()[i];
				boolean air = want == Blocks.AIR;
				if (!air) {
					tally[2][step]++;
				}
				if (column[index] == want) {
					tally[0][step]++;
					if (!air) {
						tally[1][step]++;
					}
				} else {
					tally[0][step]--;
				}
			}
		}
		int chunks = tallies.size();
		int[] chunkBest = new int[chunks];
		for (int c = 0; c < chunks; c++) {
			int[] score = tallies.get(c)[0];
			for (int step = 1; step < tries; step++) {
				if (score[step] > score[chunkBest[c]]) {
					chunkBest[c] = step;
				}
			}
		}
		// The heights of the chunks whose part is there (any of its blocks, half of those that can be read), or if none
		// is, of every chunk: a part mostly missing or overbuilt can fit best anywhere.
		boolean anyThere = false;
		boolean[] there = new boolean[chunks];
		for (int c = 0; c < chunks; c++) {
			int[][] tally = tallies.get(c);
			there[c] = found(tally[1][chunkBest[c]], tally[2][chunkBest[c]], 1);
			anyThere |= there[c];
		}
		int best = -1;
		int bestScore = Integer.MIN_VALUE;
		for (int c = 0; c < chunks; c++) {
			if (isEmpty(tallies.get(c)) || anyThere && !there[c]) {
				continue;
			}
			int step = chunkBest[c];
			int score = 0;
			for (int[][] tally : tallies) {
				score += tally[0][step];
			}
			if (score > bestScore || score == bestScore && step < best) {
				bestScore = score;
				best = step;
			}
		}
		if (best < 0) {
			return null;
		}
		// Found by the whole piece, or by the chunks whose part is there when the rest is at another height.
		int solid = 0;
		int readable = 0;
		int solidHere = 0;
		int readableHere = 0;
		for (int c = 0; c < chunks; c++) {
			int[][] tally = tallies.get(c);
			solid += tally[1][best];
			readable += tally[2][best];
			if (chunkBest[c] == best) {
				solidHere += tally[1][best];
				readableHere += tally[2][best];
			}
		}
		return found(solid, readable, foundMin) || found(solidHere, readableHere, foundMin) ? tried(at, best) : null;
	}

	private static boolean found(int solid, int readable, int foundMin) {
		return solid >= Math.max(1, foundMin) && solid >= FOUND_SHARE * readable;
	}

	/** The {@code step}th height tried for the bottom of a box now at {@code at}: 0, +1, -1, +2, -2... from there. */
	private static int tried(Box at, int step) {
		return at.minY() + (step % 2 == 1 ? (step + 1) / 2 : -step / 2);
	}

	/** True when none of a chunk's columns were there to read. */
	private static boolean isEmpty(int[][] tally) {
		for (int step = 0; step < tally[0].length; step++) {
			if (tally[0][step] != 0 || tally[2][step] != 0) {
				return false;
			}
		}
		return true;
	}

	/** The block as matched: air for any air, the plain block for the game's random variants of it. */
	static Block plain(BlockState state) {
		return state.isAir() ? Blocks.AIR : PLAIN.computeIfAbsent(state.getBlock(), SeedSnapping::plainOf);
	}

	private static Block plainOf(Block block) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		String path = id.getPath();
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String prefix : VARIANT_PREFIXES) {
				if (path.startsWith(prefix)) {
					path = path.substring(prefix.length());
					changed = true;
				}
			}
		}
		Identifier plain = Identifier.fromNamespaceAndPath(id.getNamespace(), path);
		return !path.equals(id.getPath()) && BuiltInRegistries.BLOCK.containsKey(plain) ? BuiltInRegistries.BLOCK.getValue(plain) : block;
	}

	/**
	 * The blocks the piece places where it's planned, relative to its box once placed. Templates are placed without
	 * what ruins them or adds to them (integrity, suspicious blocks, entities); other pieces are built by their own code
	 * on ground just under their box. Null when the piece couldn't be built here. The piece is used up: it may move.
	 */
	private static Shapes shapes(SeedDimension dimension, SeedDimension.Planned planned, StructurePiece piece) {
		try {
			if (piece instanceof TemplateStructurePiece template) {
				StructurePlaceSettings original = template.placeSettings();
				StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(original.getRotation()).setMirror(original.getMirror())
					.setRotationPivot(original.getRotationPivot()).setIgnoreEntities(true).setKnownShape(true)
					.setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
				for (StructureProcessor processor : original.getProcessors()) {
					if (processor instanceof BlockIgnoreProcessor) {
						settings.addProcessor(processor);
					}
				}
				BoundingBox box = StructureInfos.pieceBox(template, dimension.templates());
				// Each palette in turn: the random the game picks one with picks this one, and says how many there are.
				int[] count = {1};
				List<Shape> palettes = new ArrayList<>();
				for (int palette = 0; palette < count[0]; palette++) {
					Map<Long, BlockState> placed = new HashMap<>();
					settings.setRandom(paletteChooser(palette, count));
					template.template().placeInWorld(level(dimension, placed, box.minY(), RandomSource.create(0L)), template.templatePosition(),
						template.templatePosition(), settings, RandomSource.create(0L), 2);
					palettes.add(shape(placed, box));
				}
				return new Shapes(palettes, template.templatePosition(), box.minY());
			} else {
				Map<Long, BlockState> placed = new HashMap<>();
				BoundingBox planned0 = piece.getBoundingBox();
				ChunkPos chunk = planned.start().getChunkPos();
				RandomSource random = dimension.decorationRandom(planned.structure().value(), chunk.x(), chunk.z());
				WorldGenLevel level = level(dimension, placed, planned0.minY(), random);
				BoundingBox everywhere = new BoundingBox(planned0.minX() - 16, dimension.heightAccessor().getMinY(), planned0.minZ() - 16,
					planned0.maxX() + 16, dimension.heightAccessor().getMaxY(), planned0.maxZ() + 16);
				try {
					piece.postProcess(level, null, dimension.generator(), random, everywhere, chunk, planned0.getCenter());
				} catch (EntitiesLeftOut done) {
					// Its mobs come after its blocks.
				}
				return new Shapes(List.of(shape(placed, piece.getBoundingBox())), null, 0);
			}
		} catch (RuntimeException e) {
			if (FAILED.putIfAbsent(planned.id(), true) == null) {
				LOGGER.warn("Could not build {}'s pieces to find them by their blocks", planned.id(), e);
			}
			return null;
		}
	}

	/** The blocks placed, relative to the corner of the box. */
	private static Shape shape(Map<Long, BlockState> placed, BoundingBox box) {
		int n = placed.size();
		int[] x = new int[n];
		int[] y = new int[n];
		int[] z = new int[n];
		Block[] blocks = new Block[n];
		int i = 0;
		for (Map.Entry<Long, BlockState> entry : placed.entrySet()) {
			long pos = entry.getKey();
			x[i] = BlockPos.getX(pos) - box.minX();
			y[i] = BlockPos.getY(pos) - box.minY();
			z[i] = BlockPos.getZ(pos) - box.minZ();
			blocks[i] = plain(entry.getValue());
			i++;
		}
		return new Shape(x, y, z, blocks);
	}

	/**
	 * A random for {@code StructurePlaceSettings} that makes a template's palette this one (or its last), and puts how
	 * many palettes it has in {@code count}. It's only asked for that.
	 */
	private static RandomSource paletteChooser(int palette, int[] count) {
		InvocationHandler handler = (proxy, method, args) -> {
			if (method.getName().equals("nextInt") && args != null && args.length == 1) {
				int bound = (int) args[0];
				count[0] = bound;
				return Math.min(palette, bound - 1);
			}
			throw new UnsupportedOperationException(method.toString());
		};
		return (RandomSource) Proxy.newProxyInstance(RandomSource.class.getClassLoader(), new Class<?>[] {RandomSource.class}, handler);
	}

	/** Thrown where a piece being built would add its mobs, which need a real world; its blocks are all placed by then. */
	private static final class EntitiesLeftOut extends RuntimeException {
		EntitiesLeftOut() {
			super(null, null, false, false);
		}
	}

	/**
	 * A stand-in world to build a piece in: it keeps the blocks placed, has ground (stone) under {@code ground} and air
	 * above, height maps at {@code ground}, and no fluids, block entities or mobs. What else the piece asks of it fails,
	 * unless it returns nothing (updates, sounds), which is skipped.
	 */
	private static WorldGenLevel level(SeedDimension dimension, Map<Long, BlockState> placed, int ground, RandomSource random) {
		LevelHeightAccessor height = dimension.heightAccessor();
		ChunkAccess[] chunk = new ChunkAccess[1];
		InvocationHandler handler = new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				switch (method.getName()) {
					case "getBlockState":
						return state((BlockPos) args[0]);
					case "getFluidState":
						return state((BlockPos) args[0]).getFluidState();
					case "isStateAtPosition":
						return predicate(args[1]).test(state((BlockPos) args[0]));
					case "isFluidAtPosition":
						return predicate(args[1]).test(state((BlockPos) args[0]).getFluidState());
					case "getBlockEntity":
						if (args.length == 1) {
							return null;
						}
						break;
					case "setBlock":
						if (args.length == 4) {
							placed.put(((BlockPos) args[0]).asLong(), (BlockState) args[1]);
							return true;
						}
						break;
					case "getHeight":
						if (args == null || args.length == 0) {
							return height.getHeight();
						}
						if (args.length == 3 && args[0] instanceof Heightmap.Types) {
							return ground;
						}
						break;
					case "getHeightmapPos":
						BlockPos pos = (BlockPos) args[1];
						return new BlockPos(pos.getX(), ground, pos.getZ());
					case "getMinY":
						return height.getMinY();
					case "getRandom":
						return random;
					case "getSeed":
						return dimension.seed();
					case "registryAccess":
						return dimension.registries();
					case "getChunk":
						if (args.length == 4) {
							// For the blocks (fences, torches) the game reshapes once their neighbours are there.
							if (chunk[0] == null) {
								chunk[0] = new ProtoChunk(new ChunkPos((int) args[0], (int) args[1]), UpgradeData.EMPTY, height, dimension.containers(), null);
							}
							return chunk[0];
						}
						break;
					case "getLevel":
						throw new EntitiesLeftOut();
					case "hashCode":
						if (args == null) {
							return System.identityHashCode(proxy);
						}
						break;
					case "equals":
						if (args != null && args.length == 1) {
							return proxy == args[0];
						}
						break;
					case "toString":
						if (args == null) {
							return "SeedSnapping stand-in world";
						}
						break;
					default:
						break;
				}
				if (method.isDefault()) {
					return InvocationHandler.invokeDefault(proxy, method, args);
				}
				if (Modifier.isAbstract(method.getModifiers()) && method.getReturnType() == void.class) {
					return null;
				}
				throw new UnsupportedOperationException(method.toString());
			}

			private BlockState state(BlockPos pos) {
				BlockState state = placed.get(pos.asLong());
				if (state != null) {
					return state;
				}
				return pos.getY() < ground ? Blocks.STONE.defaultBlockState()
					: Blocks.AIR.defaultBlockState();
			}

			@SuppressWarnings("unchecked")
			private static <T> Predicate<T> predicate(Object predicate) {
				return (Predicate<T>) predicate;
			}
		};
		return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, handler);
	}
}
