package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldTypeTest {
	@Test
	void keysAreTheWorldPresetIds() {
		assertEquals("normal", WorldType.NORMAL.key());
		assertEquals("large_biomes", WorldType.LARGE_BIOMES.key());
		assertEquals("amplified", WorldType.AMPLIFIED.key());
	}

	@Test
	void everyTypeIsFoundByItsKey() {
		for (WorldType type : WorldType.values()) {
			assertEquals(type, WorldType.byKey(type.key()));
		}
	}

	@Test
	void keysAreUnique() {
		Set<String> keys = new HashSet<>();
		for (WorldType type : WorldType.values()) {
			assertTrue(keys.add(type.key()), type.key());
		}
	}

	@Test
	void byKeyIgnoresCaseAndSpaces() {
		assertEquals(WorldType.LARGE_BIOMES, WorldType.byKey(" Large_Biomes "));
		assertEquals(WorldType.AMPLIFIED, WorldType.byKey("AMPLIFIED"));
	}

	@Test
	void unknownKeysAreNull() {
		assertNull(WorldType.byKey(null));
		assertNull(WorldType.byKey(""));
		assertNull(WorldType.byKey("flat"));
		assertNull(WorldType.byKey("minecraft:normal"));
		assertNull(WorldType.byKey("large biomes"));
	}
}
