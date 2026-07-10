/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import java.util.List;

/**
 * Curated fine-grained place names for spots the region→name table is too
 * coarse on or misses, loaded once from {@code places.tsv} coordinate boxes.
 * Checked before {@link AreaNames} — most-specific layer wins.
 */
public final class Places
{
	private static final List<CoordBoxes.Box> BOXES = CoordBoxes.load("places.tsv");

	private Places()
	{
	}

	/**
	 * @param plane reserved — the data is plane-agnostic today; a plane column
	 *              gets added only if a real collision needs it
	 * @return the place name containing the world point, or null if unknown
	 */
	public static String get(int worldX, int worldY, int plane)
	{
		return CoordBoxes.lookup(BOXES, worldX, worldY);
	}
}
