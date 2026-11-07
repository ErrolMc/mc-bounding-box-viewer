package com.errolmc.boundingboxviewer.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureKindTest {
	/** Every structure in 26.3's {@code BuiltinStructures}, with the kind it should be. */
	private static final Map<String, StructureKind> VANILLA = new HashMap<>();

	static {
		put(StructureKind.PILLAGER_OUTPOST, "pillager_outpost");
		put(StructureKind.MINESHAFT, "mineshaft", "mineshaft_mesa");
		put(StructureKind.MANSION, "mansion");
		put(StructureKind.JUNGLE_PYRAMID, "jungle_pyramid");
		put(StructureKind.DESERT_PYRAMID, "desert_pyramid");
		put(StructureKind.IGLOO, "igloo");
		put(StructureKind.SHIPWRECK, "shipwreck", "shipwreck_beached");
		put(StructureKind.SWAMP_HUT, "swamp_hut");
		put(StructureKind.STRONGHOLD, "stronghold");
		put(StructureKind.MONUMENT, "monument");
		put(StructureKind.OCEAN_RUIN, "ocean_ruin_cold", "ocean_ruin_warm");
		put(StructureKind.FORTRESS, "fortress");
		put(StructureKind.NETHER_FOSSIL, "nether_fossil");
		put(StructureKind.END_CITY, "end_city");
		put(StructureKind.BURIED_TREASURE, "buried_treasure");
		put(StructureKind.BASTION_REMNANT, "bastion_remnant");
		put(StructureKind.VILLAGE, "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");
		put(StructureKind.RUINED_PORTAL, "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
			"ruined_portal_mountain", "ruined_portal_ocean", "ruined_portal_nether");
		put(StructureKind.ANCIENT_CITY, "ancient_city");
		put(StructureKind.TRAIL_RUINS, "trail_ruins");
		put(StructureKind.TRIAL_CHAMBERS, "trial_chambers");
		put(StructureKind.ABANDONED_CAMP, "abandoned_camp_bamboo_jungle", "abandoned_camp_birch_forest", "abandoned_camp_cherry_grove",
			"abandoned_camp_dappled_forest", "abandoned_camp_flower_forest", "abandoned_camp_forest", "abandoned_camp_meadow",
			"abandoned_camp_old_growth_birch_forest", "abandoned_camp_old_growth_pine_taiga", "abandoned_camp_old_growth_spruce_taiga",
			"abandoned_camp_pale_garden", "abandoned_camp_savanna", "abandoned_camp_snowy_taiga", "abandoned_camp_sparse_jungle",
			"abandoned_camp_swamp", "abandoned_camp_taiga", "abandoned_camp_windswept_forest", "abandoned_camp_wooded_badlands");
	}

	private static void put(StructureKind kind, String... paths) {
		for (String path : paths) {
			VANILLA.put("minecraft:" + path, kind);
		}
	}

	@Test
	void everyVanillaStructureHasItsKind() {
		VANILLA.forEach((id, kind) -> assertEquals(kind, StructureKind.of(id), id));
	}

	@Test
	void everyKindButOtherIsUsed() {
		for (StructureKind kind : StructureKind.values()) {
			assertTrue(kind == StructureKind.OTHER || VANILLA.containsValue(kind), kind + " matches no vanilla structure");
		}
	}

	@Test
	void modStructuresAreOther() {
		assertEquals(StructureKind.OTHER, StructureKind.of("dungeons_arise:jungle_tree_house"));
		// Same path as a vanilla one, other namespace.
		assertEquals(StructureKind.OTHER, StructureKind.of("mymod:village_plains"));
		// A vanilla id this version doesn't know, sharing no prefix.
		assertEquals(StructureKind.OTHER, StructureKind.of("minecraft:sky_temple"));
		// Starts like a kind but isn't a variant of it.
		assertEquals(StructureKind.OTHER, StructureKind.of("minecraft:villages"));
	}

	@Test
	void variants() {
		assertEquals("Plains", StructureKind.VILLAGE.variant("minecraft:village_plains"));
		assertEquals("Cherry Grove", StructureKind.ABANDONED_CAMP.variant("minecraft:abandoned_camp_cherry_grove"));
		assertEquals("Old Growth Spruce Taiga", StructureKind.ABANDONED_CAMP.variant("minecraft:abandoned_camp_old_growth_spruce_taiga"));
		assertEquals("Nether", StructureKind.RUINED_PORTAL.variant("minecraft:ruined_portal_nether"));
		assertEquals("Mesa", StructureKind.MINESHAFT.variant("minecraft:mineshaft_mesa"));
		assertEquals("", StructureKind.RUINED_PORTAL.variant("minecraft:ruined_portal"));
		assertEquals("", StructureKind.SWAMP_HUT.variant("minecraft:swamp_hut"));
		assertEquals("", StructureKind.OTHER.variant("mymod:big_tower"));
	}

	@Test
	void keysRoundTrip() {
		for (StructureKind kind : StructureKind.values()) {
			assertEquals(kind, StructureKind.byKey(kind.key()));
		}
		assertNull(StructureKind.byKey("nope"));
	}

	@Test
	void coloursDifferWithinADimension() {
		Map<Integer, StructureKind> seen = new HashMap<>();
		for (StructureKind kind : StructureKind.values()) {
			if (kind == StructureKind.OTHER) {
				continue;
			}
			int color = kind.color("minecraft:" + kind.key());
			assertTrue(color > 0 && color <= 0xFFFFFF, kind + " colour " + Integer.toHexString(color));
			// The Overworld and the Nether can repeat each other's colours: they are never on screen together.
			int key = color | (kind.dimension() == StructureKind.Dimension.OVERWORLD ? 0 : kind.dimension().ordinal() << 24);
			StructureKind before = seen.put(key, kind);
			assertNull(before, kind + " has the same colour as " + before);
		}
	}

	@Test
	void otherColoursAreStableAndBright() {
		int tower = StructureKind.OTHER.color("mymod:big_tower");
		assertEquals(tower, StructureKind.OTHER.color("mymod:big_tower"));
		assertNotEquals(tower, StructureKind.OTHER.color("mymod:big_towers"));
		for (String id : new String[] {"a:b", "mymod:big_tower", "dungeons_arise:jungle_tree_house", "x:y_z"}) {
			int color = StructureKind.OTHER.color(id);
			int brightest = Math.max(color >> 16 & 0xFF, Math.max(color >> 8 & 0xFF, color & 0xFF));
			assertEquals(255, brightest, id + " colour " + Integer.toHexString(color));
		}
	}

	@Test
	void hsv() {
		assertEquals(0xFF0000, StructureKind.hsvToRgb(0.0F, 1.0F, 1.0F));
		assertEquals(0x00FF00, StructureKind.hsvToRgb(1.0F / 3, 1.0F, 1.0F));
		assertEquals(0x0000FF, StructureKind.hsvToRgb(2.0F / 3, 1.0F, 1.0F));
		assertEquals(0xFFFFFF, StructureKind.hsvToRgb(0.5F, 0.0F, 1.0F));
	}
}
