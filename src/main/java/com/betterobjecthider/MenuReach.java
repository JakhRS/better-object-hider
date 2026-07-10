/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

/**
 * Which hide reaches the right-click menu offers. Purely a menu-clutter
 * preference: it trims the options shown, never what existing hides do, and
 * Unhide options are unaffected so any hide stays removable in-world.
 */
public enum MenuReach
{
	ALL("Tile, area & everywhere"),
	TILE_AREA("Tile & area"),
	TILE_ONLY("Tile only");

	private final String label;

	MenuReach(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
