package com.errolmc.boundingboxviewer.client.config;

import com.errolmc.boundingboxviewer.client.input.BoxKeys;
import com.errolmc.boundingboxviewer.client.overlay.BoxOverlay;
import com.errolmc.boundingboxviewer.client.overlay.Labels;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Cloth Config settings screen. Only loaded when Cloth Config is installed. */
final class ConfigScreen {
	private ConfigScreen() {
	}

	static Screen create(Screen parent) {
		BoxConfig config = BoxConfig.get();
		BoxConfig defaults = new BoxConfig();

		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Component.translatable("bounding-box-viewer.config.title"))
			.setSavingRunnable(() -> {
				BoxConfig.save();
				BoxOverlay.rebuildSoon();
			});
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory display = builder.getOrCreateCategory(Component.translatable("bounding-box-viewer.config.category.display"));
		display.addEntry(entries.startTextDescription(Component.translatable("bounding-box-viewer.config.keys_info", BoxKeys.toggleKeyName())).build());
		display.addEntry(entries.startIntSlider(text("range"), config.range, BoxConfig.MIN_RANGE, BoxConfig.MAX_RANGE)
			.setDefaultValue(defaults.range)
			.setTooltip(tooltip("range"))
			.setSaveConsumer(value -> config.range = value)
			.build());
		display.addEntry(entries.startBooleanToggle(text("see_through"), config.seeThrough)
			.setDefaultValue(defaults.seeThrough)
			.setTooltip(tooltip("see_through"))
			.setSaveConsumer(value -> config.seeThrough = value)
			.build());
		display.addEntry(entries.startBooleanToggle(text("show_pieces"), config.showPieces)
			.setDefaultValue(defaults.showPieces)
			.setTooltip(tooltip("show_pieces"))
			.setSaveConsumer(value -> config.showPieces = value)
			.build());
		display.addEntry(entries.startBooleanToggle(text("shade_spawn_areas"), config.shadeSpawnAreas)
			.setDefaultValue(defaults.shadeSpawnAreas)
			.setTooltip(tooltip("shade_spawn_areas"))
			.setSaveConsumer(value -> config.shadeSpawnAreas = value)
			.build());
		display.addEntry(entries.startBooleanToggle(text("show_labels"), config.showLabels)
			.setDefaultValue(defaults.showLabels)
			.setTooltip(tooltip("show_labels"))
			.setSaveConsumer(value -> config.showLabels = value)
			.build());

		ConfigCategory structures = builder.getOrCreateCategory(Component.translatable("bounding-box-viewer.config.category.structures"));
		structures.addEntry(entries.startTextDescription(Component.translatable("bounding-box-viewer.config.structures_info")).build());
		for (StructureKind.Dimension dimension : StructureKind.Dimension.values()) {
			List<AbstractConfigListEntry> toggles = new ArrayList<>();
			for (StructureKind kind : StructureKind.values()) {
				if (kind.dimension() != dimension) {
					continue;
				}
				toggles.add(entries.startBooleanToggle(Labels.kindName(kind).copy().withColor(kind == StructureKind.OTHER ? 0xFFFFFF : kind.color("")), config.shows(kind))
					.setDefaultValue(true)
					.setSaveConsumer(value -> config.setShown(kind, value))
					.build());
			}
			structures.addEntry(entries.startSubCategory(Component.translatable("bounding-box-viewer.config.dimension." + dimension.name().toLowerCase()), toggles)
				.setExpanded(true)
				.build());
		}

		return builder.build();
	}

	private static Component text(String key) {
		return Component.translatable("bounding-box-viewer.config." + key);
	}

	/** Cloth Config does not wrap tooltips, so the translations put a newline where each line should end. */
	private static Component[] tooltip(String key) {
		String text = Component.translatable("bounding-box-viewer.config." + key + ".tooltip").getString();
		return Arrays.stream(text.split("\n")).map(Component::literal).toArray(Component[]::new);
	}
}
