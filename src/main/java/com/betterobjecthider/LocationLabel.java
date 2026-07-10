/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

/**
 * Builds the human-readable location line for a hidden-object row: specific
 * place plus broad context, e.g. {@code Castle Wars (Kandarin)}. Static data
 * layers, most-specific wins — curated {@link Places} boxes, then the nearest
 * {@link MapLabels world-map label}, then the {@link AreaNames} region table,
 * with {@link Provinces} supplying the parenthesised context. Instances need
 * no special path: entries store the de-instanced template location, so the
 * place layers yield the lair name wherever the data covers it. Pure logic,
 * unit-tested; keeps work off the EDT panel code.
 */
public final class LocationLabel
{
	/**
	 * How far (in tiles, Chebyshev) a world-map label reaches. Tile hides use
	 * the tight radius; area hides measure from the region centre, which can be
	 * up to 32 tiles from any tile in the region, so they get a looser one.
	 */
	static final int LABEL_RADIUS_TILE = 24;
	static final int LABEL_RADIUS_AREA = 32;

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

		final String regionName = AreaNames.get(entry.getRegionId());
		String place = Places.get(x, y, entry.getPlane());
		if (area)
		{
			// An AREA hide covers the whole region, so the region's own name is
			// the honest label; a point label (sampled at the centre, radius 32)
			// is only a gap-filler — it can sit in an adjacent region and must
			// never displace an exact region name.
			if (place == null)
			{
				place = regionName;
			}
			if (place == null)
			{
				place = MapLabels.nearest(x, y, LABEL_RADIUS_AREA);
			}
		}
		else
		{
			if (place == null)
			{
				place = MapLabels.nearest(x, y, LABEL_RADIUS_TILE);
			}
			if (place == null)
			{
				place = regionName;
			}
		}
		// Context: the province on the surface; underground there is none, so fall
		// back to the region table — "Blue dragons (Taverley Dungeon)"
		String context = Provinces.get(x, y);
		if (context == null)
		{
			context = regionName;
		}
		final String combined = combine(place, context);

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
