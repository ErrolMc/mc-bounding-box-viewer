package com.errolmc.boundingboxviewer.client.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's own world generation data (biomes, noise, structures, structure sets, template pools, world presets) and
 * structure templates, read from the vanilla data pack inside the game as a server would read them. A server never
 * sends these to the game, so seed mode loads them itself, once.
 */
public final class VanillaWorldgen {
	private static final Logger LOGGER = LoggerFactory.getLogger("bounding-box-viewer");

	private static VanillaWorldgen instance;

	private final RegistryAccess.Frozen registries;
	private final StructureTemplateManager templates;

	private VanillaWorldgen(RegistryAccess.Frozen registries, StructureTemplateManager templates) {
		this.registries = registries;
		this.templates = templates;
	}

	/** Loaded on first use (a second or two), then kept. */
	public static synchronized VanillaWorldgen get() {
		if (instance == null) {
			long started = System.nanoTime();
			instance = load();
			LOGGER.info("Loaded vanilla world generation for seed mode in {} ms", (System.nanoTime() - started) / 1_000_000);
		}
		return instance;
	}

	public RegistryAccess.Frozen registries() {
		return registries;
	}

	public StructureTemplateManager templates() {
		return templates;
	}

	private static VanillaWorldgen load() {
		// Kept open: structure templates are read from it as structures need them.
		CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
		// As WorldLoader does: the built-in registries with the data pack's tags for them, then the worldgen registries.
		LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen context = layers.getAccessForLoading(RegistryLayer.WORLD);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(context, staticTags);
		RegistryAccess.Frozen world = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
		RegistryAccess.Frozen registries = layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess();
		return new VanillaWorldgen(registries, templates(resources, registries));
	}

	/**
	 * The template manager wants a world folder for templates saved with structure blocks; seed mode has none, so it
	 * gets an empty one that is let go of at once.
	 */
	private static StructureTemplateManager templates(CloseableResourceManager resources, RegistryAccess registries) {
		try {
			Path folder = Files.createTempDirectory("bounding-box-viewer-");
			folder.toFile().deleteOnExit();
			try (LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(folder).createAccess("templates")) {
				return new StructureTemplateManager(resources, access, DataFixers.getDataFixer(), registries.lookupOrThrow(Registries.BLOCK));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not make a folder for seed mode's structure templates", e);
		}
	}
}
