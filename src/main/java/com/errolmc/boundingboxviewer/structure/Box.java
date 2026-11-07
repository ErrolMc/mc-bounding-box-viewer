package com.errolmc.boundingboxviewer.structure;

/**
 * A box of whole blocks, both corners inclusive, as the game's {@code BoundingBox}: blocks {@code minX..maxX} and so on,
 * so the box's faces are at {@code minX} and {@code maxX + 1} in world coordinates.
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public Box {
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("corners out of order: " + minX + " " + minY + " " + minZ + " / " + maxX + " " + maxY + " " + maxZ);
		}
	}

	/** Squared distance across the ground (x and z only) from a point to the box's columns: 0 above, below or inside it. */
	public double horizontalDistanceSquared(double x, double z) {
		double dx = gap(x, minX, maxX + 1);
		double dz = gap(z, minZ, maxZ + 1);
		return dx * dx + dz * dz;
	}

	/** Squared distance in 3D from a point to the nearest point of the box: 0 inside it. */
	public double distanceSquared(double x, double y, double z) {
		double dx = gap(x, minX, maxX + 1);
		double dy = gap(y, minY, maxY + 1);
		double dz = gap(z, minZ, maxZ + 1);
		return dx * dx + dy * dy + dz * dz;
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	/** Whether every block of the other box is in this one. */
	public boolean encloses(Box other) {
		return other.minX >= minX && other.maxX <= maxX && other.minY >= minY && other.maxY <= maxY && other.minZ >= minZ && other.maxZ <= maxZ;
	}

	public double centerX() {
		return (minX + maxX + 1) / 2.0;
	}

	public double centerZ() {
		return (minZ + maxZ + 1) / 2.0;
	}

	/** The height of the box's top face. */
	public int top() {
		return maxY + 1;
	}

	public int xSpan() {
		return maxX - minX + 1;
	}

	public int ySpan() {
		return maxY - minY + 1;
	}

	public int zSpan() {
		return maxZ - minZ + 1;
	}

	private static double gap(double value, double low, double high) {
		if (value < low) {
			return low - value;
		}
		if (value > high) {
			return value - high;
		}
		return 0;
	}
}
