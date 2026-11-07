package com.errolmc.boundingboxviewer.gametest.mixin;

import com.errolmc.boundingboxviewer.gametest.ChunkSaving;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test only, while {@link ChunkSaving#skipped()}: no saving every chunk at once (autosave), and no saving chunks once
 * the server is stopping (it unloads every chunk first, each one saved as it goes).
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
	private void boundingBoxViewerGametest$skipSaving(boolean flush, CallbackInfo info) {
		if (ChunkSaving.skipped()) {
			info.cancel();
		}
	}

	@Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
	private void boundingBoxViewerGametest$skipSavingOnStop(ChunkAccess chunk, CallbackInfoReturnable<Boolean> info) {
		if (ChunkSaving.skipped() && !level.getServer().isRunning()) {
			info.setReturnValue(false);
		}
	}
}
