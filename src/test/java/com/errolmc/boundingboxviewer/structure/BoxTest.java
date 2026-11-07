package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxTest {
	/** A swamp hut's size: blocks 0..6 across, 0..8 up, 0..8 deep. */
	private static final Box HUT = new Box(0, 64, 0, 6, 72, 8);

	@Test
	void sizeAndCentre() {
		assertEquals(7, HUT.xSpan());
		assertEquals(9, HUT.ySpan());
		assertEquals(9, HUT.zSpan());
		assertEquals(3.5, HUT.centerX());
		assertEquals(4.5, HUT.centerZ());
		assertEquals(73, HUT.top());
	}

	@Test
	void horizontalDistanceIgnoresHeight() {
		assertEquals(0, HUT.horizontalDistanceSquared(3, 4));
		// The far faces are at max + 1, so standing on the last block row is inside.
		assertEquals(0, HUT.horizontalDistanceSquared(6.9, 8.9));
		assertEquals(0, HUT.horizontalDistanceSquared(7, 9));
		assertEquals(9, HUT.horizontalDistanceSquared(10, 4));
		assertEquals(16, HUT.horizontalDistanceSquared(3, -4));
		assertEquals(3 * 3 + 4 * 4, HUT.horizontalDistanceSquared(-3, 13));
	}

	@Test
	void distanceInThreeDimensions() {
		assertEquals(0, HUT.distanceSquared(3, 70, 4));
		assertEquals(100, HUT.distanceSquared(3, 83, 4));
		assertEquals(4 + 100, HUT.distanceSquared(-2, 54, 4));
	}

	@Test
	void contains() {
		assertTrue(HUT.contains(0, 64, 0));
		assertTrue(HUT.contains(6, 72, 8));
		assertFalse(HUT.contains(7, 72, 8));
		assertFalse(HUT.contains(3, 63, 4));
	}

	@Test
	void encloses() {
		assertTrue(HUT.encloses(HUT));
		assertTrue(HUT.encloses(new Box(1, 65, 1, 5, 71, 7)));
		assertFalse(HUT.encloses(new Box(1, 63, 1, 5, 71, 7)));
		assertFalse(HUT.encloses(new Box(1, 65, 1, 7, 71, 7)));
		assertFalse(new Box(1, 65, 1, 5, 71, 7).encloses(HUT));
	}

	@Test
	void singleBlock() {
		Box block = new Box(5, 5, 5, 5, 5, 5);
		assertEquals(1, block.xSpan());
		assertEquals(5.5, block.centerX());
	}

	@Test
	void cornersMustBeInOrder() {
		assertThrows(IllegalArgumentException.class, () -> new Box(1, 0, 0, 0, 0, 0));
	}
}
