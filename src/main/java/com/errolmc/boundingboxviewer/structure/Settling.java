package com.errolmc.boundingboxviewer.structure;

/**
 * Where pieces that settle onto the ground end up, worked out the way the game does it when their chunk is decorated
 * but from a height map given here (in seed mode, the terrain the generator makes before anything is built on it).
 * Each method returns the new height of the piece's bottom or of its template's corner; the piece's box moves by the
 * difference.
 */
public final class Settling {
	/** The ground height at a column: the y of the first block above the ground, as a height map gives it. */
	@FunctionalInterface
	public interface Heights {
		int at(int x, int z);
	}

	private Settling() {
	}

	/**
	 * Swamp huts, jungle temples and shipwrecks: the average ground height under the given columns (inclusive corners,
	 * either order), dropping any fraction as the game's integer division does.
	 */
	public static int average(int x1, int z1, int x2, int z2, Heights heights) {
		long total = 0;
		int count = 0;
		for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				total += heights.at(x, z);
				count++;
			}
		}
		return (int) (total / count);
	}

	/** Desert pyramids and beached shipwrecks: the lowest ground height under the given columns. */
	public static int lowest(int x1, int z1, int x2, int z2, Heights heights) {
		int lowest = Integer.MAX_VALUE;
		for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
				lowest = Math.min(lowest, heights.at(x, z));
			}
		}
		return lowest;
	}

	/**
	 * An ocean ruin's new corner height. The game puts the corner on the sea floor under it, then looks down from there
	 * over the ruin's whole area: where the floor drops more than 2 blocks below the corner in enough of it (more than its
	 * width less 2 columns), the ruin sinks to one above the lowest floor, so it doesn't hang over a slope.
	 *
	 * @param x the template's corner
	 * @param cornerX the opposite corner, after the ruin's rotation
	 * @param floor the ocean floor height map: one above the top block that isn't water or air
	 */
	public static int oceanRuin(int x, int z, int cornerX, int cornerZ, Heights floor) {
		int corner = floor.at(x, z);
		int top = corner - 1;
		int lowest = Integer.MAX_VALUE;
		int deeper = 0;
		for (int pz = Math.min(z, cornerZ); pz <= Math.max(z, cornerZ); pz++) {
			for (int px = Math.min(x, cornerX); px <= Math.max(x, cornerX); px++) {
				// Down from the corner's floor through water: stops on this column's floor, or at once if that is higher.
				int floorY = Math.min(top, floor.at(px, pz) - 1);
				lowest = Math.min(lowest, floorY);
				if (floorY < top - 2) {
					deeper++;
				}
			}
		}
		int width = Math.abs(x - cornerX);
		return top - lowest > 2 && deeper > width - 2 ? lowest + 1 : corner;
	}
}
