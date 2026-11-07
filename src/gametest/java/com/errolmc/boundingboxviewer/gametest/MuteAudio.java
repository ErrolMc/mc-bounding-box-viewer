package com.errolmc.boundingboxviewer.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * Keeps the test client silent. Runs at startup (title screen music) and again at the start of the test, because
 * the test runner resets the game options to their defaults just before it runs a test.
 */
public class MuteAudio implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(MuteAudio::mute);
	}

	static void mute(Minecraft minecraft) {
		minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
	}
}
