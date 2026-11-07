package com.errolmc.boundingboxviewer.client.config;

import com.errolmc.boundingboxviewer.structure.StructureKind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Settings, stored as {@code config/bounding-box-viewer.json}. */
public final class BoxConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("bounding-box-viewer.json");

	public static final int MIN_RANGE = 16;
	public static final int MAX_RANGE = 512;

	private static BoxConfig instance = new BoxConfig();

	/** Blocks out from the player, across the ground: structures whose box comes this close are shown, at any height. */
	public int range = 128;

	/** The box of every piece (corridors, houses, tunnels) inside the box around the whole structure. */
	public boolean showPieces = true;
	/** The structure's name over the top of its box. */
	public boolean showLabels = true;
	/** A light shading where the structure's own mobs spawn: witches, pillagers, guardians, blazes. */
	public boolean shadeSpawnAreas = true;
	/** Draw boxes through blocks; off, blocks hide them like anything else in the world. */
	public boolean seeThrough = false;

	/** {@link StructureKind#key()} of every kind switched off. */
	public Set<String> hidden = new TreeSet<>();

	public static BoxConfig get() {
		return instance;
	}

	public boolean shows(StructureKind kind) {
		return !hidden.contains(kind.key());
	}

	public void setShown(StructureKind kind, boolean shown) {
		if (shown) {
			hidden.remove(kind.key());
		} else {
			hidden.add(kind.key());
		}
	}

	public static void load() {
		if (!Files.exists(FILE)) {
			instance = new BoxConfig();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE)) {
			BoxConfig loaded = GSON.fromJson(reader, BoxConfig.class);
			instance = loaded != null ? loaded.sanitized() : new BoxConfig();
		} catch (IOException | JsonParseException e) {
			LOGGER.error("Could not read {}, using defaults", FILE, e);
			instance = new BoxConfig();
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", FILE, e);
		}
	}

	private BoxConfig sanitized() {
		range = Mth.clamp(range, MIN_RANGE, MAX_RANGE);
		// Gson fills a Set field with whatever set it likes, or null when the file has none.
		hidden = hidden == null ? new TreeSet<>() : new TreeSet<>(hidden);
		hidden.removeIf(key -> key == null || StructureKind.byKey(key) == null);
		return this;
	}
}

