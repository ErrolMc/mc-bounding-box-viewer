package com.errolmc.boundingboxviewer.gametest;

/**
 * Whether the test server saves its chunks on closing a world (it unloads every chunk, each saved as it goes, then
 * waits for the writes) and autosaving: ten seconds a world for the thousands of chunks the seed tests generate. The
 * tests never open a world again, so while they run it doesn't ({@code mixin.ChunkMapMixin}). Chunks unloaded while the
 * world is open are still saved, so they load back as they were.
 */
public final class ChunkSaving {
	private static volatile boolean skipped;

	private ChunkSaving() {
	}

	/** True while the server leaves its chunks unsaved on closing. */
	public static boolean skipped() {
		return skipped;
	}

	/** Runs the test with chunk saving skipped. */
	static void skipDuring(Runnable test) {
		skipped = true;
		try {
			test.run();
		} finally {
			skipped = false;
		}
	}
}
