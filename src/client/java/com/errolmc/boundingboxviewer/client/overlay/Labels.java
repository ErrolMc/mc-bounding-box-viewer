package com.errolmc.boundingboxviewer.client.overlay;

import com.errolmc.boundingboxviewer.client.world.StructureInfo;
import com.errolmc.boundingboxviewer.structure.StructureKind;
import net.minecraft.network.chat.Component;

/** Names shown over the boxes and in the settings. */
public final class Labels {
	private Labels() {
	}

	public static Component kindName(StructureKind kind) {
		return Component.translatable("bounding-box-viewer.structure." + kind.key());
	}

	/** "Village (Plains)", "Swamp Hut"; a mod's structure by its id. */
	public static String of(StructureInfo structure) {
		if (structure.kind() == StructureKind.OTHER) {
			return structure.id();
		}
		String name = kindName(structure.kind()).getString();
		String variant = structure.kind().variant(structure.id());
		return variant.isEmpty() ? name : name + " (" + variant + ")";
	}
}
