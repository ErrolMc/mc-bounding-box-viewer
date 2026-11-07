package com.errolmc.boundingboxviewer.structure;

import java.util.Locale;

/**
 * The structures the viewer tells apart, each with its own colour and its own switch in the settings. Variants share
 * one kind: every village, every ruined portal, both mineshafts, both ocean ruins and every abandoned camp. Structures
 * from mods and datapacks are {@link #OTHER}, each with a colour of its own worked out from its id.
 */
public enum StructureKind {
	// Overworld
	VILLAGE("village", Dimension.OVERWORLD, 0x3AA0FF),
	PILLAGER_OUTPOST("pillager_outpost", Dimension.OVERWORLD, 0xDADADA),
	SWAMP_HUT("swamp_hut", Dimension.OVERWORLD, 0xB04DFF),
	MONUMENT("monument", Dimension.OVERWORLD, 0x2EE6B8),
	MANSION("mansion", Dimension.OVERWORLD, 0x9A6233),
	DESERT_PYRAMID("desert_pyramid", Dimension.OVERWORLD, 0xFFE27A),
	JUNGLE_PYRAMID("jungle_pyramid", Dimension.OVERWORLD, 0x4CC94C),
	IGLOO("igloo", Dimension.OVERWORLD, 0xC4ECFF),
	STRONGHOLD("stronghold", Dimension.OVERWORLD, 0xFF3B3B),
	MINESHAFT("mineshaft", Dimension.OVERWORLD, 0xC98B3F),
	SHIPWRECK("shipwreck", Dimension.OVERWORLD, 0xFF7F2A),
	OCEAN_RUIN("ocean_ruin", Dimension.OVERWORLD, 0x6E9FB0),
	BURIED_TREASURE("buried_treasure", Dimension.OVERWORLD, 0xFFC400),
	ANCIENT_CITY("ancient_city", Dimension.OVERWORLD, 0x00D5E0),
	TRAIL_RUINS("trail_ruins", Dimension.OVERWORLD, 0xE0785A),
	TRIAL_CHAMBERS("trial_chambers", Dimension.OVERWORLD, 0xF2A65A),
	ABANDONED_CAMP("abandoned_camp", Dimension.OVERWORLD, 0xA8E05A),
	/** In the Overworld and the Nether. */
	RUINED_PORTAL("ruined_portal", Dimension.OVERWORLD, 0xFF4FD8),
	// Nether
	FORTRESS("fortress", Dimension.NETHER, 0xFF3B3B),
	BASTION_REMNANT("bastion_remnant", Dimension.NETHER, 0xFFC400),
	NETHER_FOSSIL("nether_fossil", Dimension.NETHER, 0xF2EAD0),
	// End
	END_CITY("end_city", Dimension.END, 0xE0A8FF),
	/** Structures from mods and datapacks, or ones this version doesn't know. */
	OTHER("other", Dimension.ANY, 0);

	/** Where a kind is found, to group the settings. */
	public enum Dimension {
		OVERWORLD, NETHER, END, ANY
	}

	private static final String VANILLA = "minecraft:";

	private final String key;
	private final Dimension dimension;
	private final int color;

	StructureKind(String key, Dimension dimension, int color) {
		this.key = key;
		this.dimension = dimension;
		this.color = color;
	}

	/** Lower-case name used in the config file and translation keys; for vanilla kinds also the id (or id prefix). */
	public String key() {
		return key;
	}

	public Dimension dimension() {
		return dimension;
	}

	/** @param id a structure id such as {@code minecraft:village_plains} */
	public static StructureKind of(String id) {
		if (!id.startsWith(VANILLA)) {
			return OTHER;
		}
		String path = id.substring(VANILLA.length());
		for (StructureKind kind : values()) {
			if (kind != OTHER && (path.equals(kind.key) || path.startsWith(kind.key + "_"))) {
				return kind;
			}
		}
		return OTHER;
	}

	/** The kind with this {@link #key()}, or null. */
	public static StructureKind byKey(String key) {
		for (StructureKind kind : values()) {
			if (kind.key.equals(key)) {
				return kind;
			}
		}
		return null;
	}

	/**
	 * The variant's name from the rest of the id, title case: "Plains" for {@code minecraft:village_plains}, "Cherry
	 * Grove" for {@code minecraft:abandoned_camp_cherry_grove}; empty for the plain one ({@code minecraft:ruined_portal})
	 * and for {@link #OTHER}.
	 */
	public String variant(String id) {
		String prefix = VANILLA + key + "_";
		if (this == OTHER || !id.startsWith(prefix)) {
			return "";
		}
		return titleCase(id.substring(prefix.length()));
	}

	/** RGB, no alpha. Each {@link #OTHER} structure gets its own, the same every time. */
	public int color(String id) {
		if (this != OTHER) {
			return color;
		}
		// Spread the hash over the hue circle; mixing first so ids that differ in one letter don't get the same hue.
		int hash = id.hashCode() * 0x9E3779B9;
		float hue = ((hash >>> 16) & 0xFFFF) / 65536.0F;
		return hsvToRgb(hue, 0.55F, 1.0F);
	}

	static String titleCase(String path) {
		StringBuilder out = new StringBuilder(path.length());
		for (String word : path.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
		}
		return out.toString();
	}

	static int hsvToRgb(float hue, float saturation, float value) {
		float h = (hue - (float) Math.floor(hue)) * 6.0F;
		int sector = (int) h % 6;
		float f = h - (int) h;
		float p = value * (1 - saturation);
		float q = value * (1 - f * saturation);
		float t = value * (1 - (1 - f) * saturation);
		float r;
		float g;
		float b;
		switch (sector) {
			case 0 -> { r = value; g = t; b = p; }
			case 1 -> { r = q; g = value; b = p; }
			case 2 -> { r = p; g = value; b = t; }
			case 3 -> { r = p; g = q; b = value; }
			case 4 -> { r = t; g = p; b = value; }
			default -> { r = value; g = p; b = q; }
		}
		return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}
}
