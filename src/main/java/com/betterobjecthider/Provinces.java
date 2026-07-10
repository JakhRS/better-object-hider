/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import java.util.List;

/**
 * Coarse province names (Misthalin, Kandarin, ...) loaded once from
 * {@code provinces.tsv} coordinate boxes. Provides the parenthesised context in
 * "Place (Province)" labels. Boxes are approximate near borders by design.
 */
public final class Provinces
{
	private static final List<CoordBoxes.Box> BOXES = CoordBoxes.load("provinces.tsv");

	private Provinces()
	{
	}

	/** @return the province containing the world point, or null if unknown */
	public static String get(int worldX, int worldY)
	{
		return CoordBoxes.lookup(BOXES, worldX, worldY);
	}
}
