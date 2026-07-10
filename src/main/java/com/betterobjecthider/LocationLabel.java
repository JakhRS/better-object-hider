/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

/**
 * Builds the human-readable location line for a hidden-object row: specific
 * place plus broad context, e.g. {@code Castle Wars (Kandarin)}. Three static
 * data layers, most-specific wins — curated {@link Places} boxes, then the
 * {@link AreaNames} region table, with {@link Provinces} supplying the
 * parenthesised context. Instances need no special path: entries store the
 * de-instanced template location, so the place layer yields the lair name
 * wherever the data covers it. Pure logic, unit-tested; keeps work off the EDT
 * panel code.
 */
public final class LocationLabel
{
	private LocationLabel()
	{
	}

	/** @return the display string for the entry's location */
	public static String describe(HideEntry entry)
	{
		if (entry.getScope() == HideScope.GLOBAL)
		{
			return "Everywhere";
		}

		// Region ID → its south-west corner; AREA entries use the region centre
		final int baseX = (entry.getRegionId() >>> 8) << 6;
		final int baseY = (entry.getRegionId() & 0xff) << 6;
		final boolean area = entry.getScope() == HideScope.AREA;
		final int x = baseX + (area ? 32 : entry.getRegionX());
		final int y = baseY + (area ? 32 : entry.getRegionY());

		String place = Places.get(x, y, entry.getPlane());
		if (place == null)
		{
			place = AreaNames.get(entry.getRegionId());
		}
		final String combined = combine(place, Provinces.get(x, y));

		if (area)
		{
			return combined != null
				? "All of " + combined
				: "This whole area (near " + baseX + ", " + baseY + ")";
		}
		if (combined != null)
		{
			return combined;
		}
		final StringBuilder sb = new StringBuilder("Tile ").append(x).append(", ").append(y);
		if (entry.getPlane() != 0)
		{
			sb.append(", plane ").append(entry.getPlane());
		}
		return sb.toString();
	}

	/** {@code place (province)} when both are known and differ, else whichever exists. */
	static String combine(String place, String province)
	{
		if (place != null && province != null && !place.equals(province))
		{
			return place + " (" + province + ")";
		}
		return place != null ? place : province;
	}
}
