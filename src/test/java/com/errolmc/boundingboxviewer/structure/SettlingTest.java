package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SettlingTest {
	private static final Settling.Heights FLAT_63 = (x, z) -> 63;
	/** Rises one block per column east: x 0 is 60, x 9 is 69. */
	private static final Settling.Heights SLOPE_EAST = (x, z) -> 60 + x;

	/** An ocean ruin over a floor given as a height map, nothing over it but water: the floor is just under it. */
	private static int oceanRuin(int x, int z, int cornerX, int cornerZ, Settling.Heights floor) {
		return Settling.oceanRuin(x, z, cornerX, cornerZ, floor.at(x, z), (px, pz, top) -> Math.min(top, floor.at(px, pz) - 1));
	}

	@Test
	void averageOfFlatGroundIsTheGround() {
		assertEquals(63, Settling.average(0, 0, 6, 8, FLAT_63));
		assertEquals(63, Settling.average(-100, -100, -94, -92, FLAT_63));
	}

	@Test
	void averageDropsTheFraction() {
		// 60..63 averages 61.5.
		assertEquals(61, Settling.average(0, 0, 3, 0, SLOPE_EAST));
		// 60..66 averages 63 exactly.
		assertEquals(63, Settling.average(0, 0, 6, 8, SLOPE_EAST));
	}

	@Test
	void averageTruncatesTowardZeroBelowZero() {
		// -2 and -1 average -1.5; the game's integer division makes that -1.
		assertEquals(-1, Settling.average(0, 0, 1, 0, (x, z) -> x == 0 ? -2 : -1));
	}

	@Test
	void averageTakesCornersInEitherOrder() {
		assertEquals(Settling.average(0, 0, 6, 8, SLOPE_EAST), Settling.average(6, 8, 0, 0, SLOPE_EAST));
		assertEquals(Settling.average(0, 8, 6, 0, SLOPE_EAST), Settling.average(6, 0, 0, 8, SLOPE_EAST));
	}

	@Test
	void averageOfOneColumnIsThatColumn() {
		assertEquals(65, Settling.average(5, 3, 5, 3, SLOPE_EAST));
	}

	@Test
	void averageCountsEveryColumnOnce() {
		// One deep hole in a 3 x 3 patch of 63: (8 * 63 + 0) / 9 = 56.
		assertEquals(56, Settling.average(0, 0, 2, 2, (x, z) -> x == 1 && z == 1 ? 0 : 63));
	}

	@Test
	void lowestFindsTheLowestColumn() {
		assertEquals(60, Settling.lowest(0, 0, 9, 9, SLOPE_EAST));
		assertEquals(63, Settling.lowest(3, 0, 9, 9, SLOPE_EAST));
		assertEquals(40, Settling.lowest(0, 0, 4, 4, (x, z) -> x == 4 && z == 2 ? 40 : 70));
	}

	@Test
	void lowestTakesCornersInEitherOrder() {
		assertEquals(Settling.lowest(9, 9, 3, 0, SLOPE_EAST), Settling.lowest(3, 0, 9, 9, SLOPE_EAST));
	}

	@Test
	void oceanRuinOnAFlatFloorStaysOnIt() {
		Settling.Heights floor = (x, z) -> 45;
		assertEquals(45, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void oceanRuinOverAGentleSlopeStaysAtItsCorner() {
		// Never more than 2 below the corner's floor: no sinking.
		Settling.Heights floor = (x, z) -> 45 - Math.min(2, x / 3);
		assertEquals(45, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void oceanRuinOverADropSinksToOneAboveTheLowestFloor() {
		// Floor falls away east: 45 at x 0, 38 at x 7.
		Settling.Heights floor = (x, z) -> 45 - x;
		// Top 44; floors 44..37; below 42 in x 3..7: 5 columns x 8 rows = 40 > width 7 - 2. Lowest 37, so 38.
		assertEquals(38, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void oceanRuinWithOnlyAFewDeepColumnsStaysPut() {
		// One deep pit: deep enough, but not in more than width - 2 columns.
		Settling.Heights floor = (x, z) -> x == 3 && z == 3 ? 30 : 45;
		assertEquals(45, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void oceanRuinNeedsMoreThanWidthLessTwoDeepColumns() {
		// Width 7: needs more than 5 columns over 2 below. Exactly 5 stays, 6 sinks.
		Settling.Heights five = (x, z) -> z == 0 && x >= 1 && x <= 5 ? 40 : 45;
		Settling.Heights six = (x, z) -> z == 0 && x >= 1 && x <= 6 ? 40 : 45;
		assertEquals(45, oceanRuin(0, 0, 7, 7, five));
		assertEquals(40, oceanRuin(0, 0, 7, 7, six));
	}

	@Test
	void oceanRuinNeedsTheDropToBeMoreThanTwo() {
		// Every other column 3 below the top (top is 44, those floors 41): 41 < 42, so they count, and 44 - 41 > 2.
		Settling.Heights three = (x, z) -> 45 - 3 * (x % 2);
		assertEquals(42, oceanRuin(0, 0, 7, 7, three));
		// 2 below: 42 is not < 42 and 44 - 42 is not > 2.
		Settling.Heights two = (x, z) -> 45 - 2 * (x % 2);
		assertEquals(45, oceanRuin(0, 0, 7, 7, two));
	}

	@Test
	void oceanRuinWideAndShallowDropDoesNotSink() {
		// 16 wide, the floor 4 lower in only the last 3 of 16 columns (x 13..15) and one row: 3 is not more than 15 - 2.
		Settling.Heights floor = (x, z) -> z == 0 && x >= 13 ? 41 : 45;
		assertEquals(45, oceanRuin(0, 0, 15, 15, floor));
	}

	@Test
	void oceanRuinCountsEveryColumnOfEveryRow() {
		// 8 x 8: deep columns spread one per row (8 of them) are more than 7 - 2.
		Settling.Heights floor = (x, z) -> x == z && x > 0 ? 40 : 45;
		assertEquals(40, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void averageAndLowestOfALargeAreaDontOverflow() {
		assertEquals(320, Settling.average(-1000, -1000, 1000, 1000, (x, z) -> 320));
		assertEquals(-64, Settling.lowest(-300, -300, 300, 300, (x, z) -> x == 299 && z == -300 ? -64 : 100));
	}

	@Test
	void oceanRuinIgnoresHigherFloor() {
		// Floor higher than the corner's everywhere else: the downward look stops at once, nothing deeper.
		Settling.Heights floor = (x, z) -> x == 0 && z == 0 ? 40 : 50;
		assertEquals(40, oceanRuin(0, 0, 7, 7, floor));
	}

	@Test
	void oceanRuinRotatedCornerWorks() {
		// A ruin rotated so its far corner is west and north of its template corner.
		Settling.Heights floor = (x, z) -> 45 + x;
		// Corner at x 0 (floor 45, top 44); columns x -7..0, floors 37..44.
		assertEquals(38, oceanRuin(0, 0, -7, -7, floor));
	}

	@Test
	void oceanRuinLooksThroughIceToTheFloor() {
		// Ice on the water over half the ruin: the corner's height map stops on the ice (50), the look down goes through it
		// to the floor at 40, 10 below the top.
		Settling.Floor floor = (x, z, top) -> Math.min(top, 39);
		assertEquals(40, Settling.oceanRuin(0, 0, 7, 7, 50, floor));
	}

	@Test
	void oceanRuinWidthIsTheXSpanOnly() {
		// A ruin 2 wide (x) and 12 deep (z), floor falling along z: width 1, so any deep column (more than -1) is enough.
		Settling.Heights floor = (x, z) -> 45 - z;
		// Top 44, lowest floor 44 - 11 = 33, so 34.
		assertEquals(34, oceanRuin(0, 0, 1, 11, floor));
		// The same drop across x needs more than 11 - 2 deep columns: 9 deep columns x 2 rows, 18.
		assertEquals(34, oceanRuin(0, 0, 11, 1, (x, z) -> 45 - x));
	}
}
