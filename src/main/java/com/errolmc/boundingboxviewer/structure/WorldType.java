package com.errolmc.boundingboxviewer.structure;

import java.util.Locale;

/** The world types a seed can be worked out for: vanilla's world presets that generate structures the normal way. */
public enum WorldType {
	NORMAL("normal"),
	LARGE_BIOMES("large_biomes"),
	AMPLIFIED("amplified");

	private final String key;

	WorldType(String key) {
		this.key = key;
	}

	/** The world preset's id path, as in {@code minecraft:large_biomes}; also how the config and the command name it. */
	public String key() {
		return key;
	}

	/** The type with this key (any case), or null. */
	public static WorldType byKey(String key) {
		if (key == null) {
			return null;
		}
		String wanted = key.trim().toLowerCase(Locale.ROOT);
		for (WorldType type : values()) {
			if (type.key.equals(wanted)) {
				return type;
			}
		}
		return null;
	}
}
