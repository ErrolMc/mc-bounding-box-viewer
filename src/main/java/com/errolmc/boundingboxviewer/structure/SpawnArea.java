package com.errolmc.boundingboxviewer.structure;

/**
 * Where a structure's own mobs spawn (the structure's {@code spawn_overrides} that have mobs in them): witches in a swamp
 * hut, pillagers at an outpost, guardians at a monument, blazes and wither skeletons in a fortress.
 */
public enum SpawnArea {
	/** No mobs of its own (or only overrides with no mobs, which stop natural spawns, as in trial chambers). */
	NONE,
	/** Inside any of the structure's pieces ({@code "bounding_box": "piece"}): swamp huts and fortresses. */
	PIECES,
	/** Anywhere in the box around the whole structure ({@code "bounding_box": "full"}): outposts and monuments. */
	WHOLE
}
