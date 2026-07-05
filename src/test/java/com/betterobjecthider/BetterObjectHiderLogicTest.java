/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BetterObjectHiderLogicTest
{
	private final Gson gson = new Gson();

	// --- coordinate keys (v1 coverage, unchanged behavior) ---------------------------

	@Test
	public void tileKeyEncodesWorldPoint()
	{
		final WorldPoint wp = new WorldPoint(3222, 3218, 0);
		assertEquals("1276:3222:3218:0", BetterObjectHiderPlugin.tileKey(1276, wp));
	}

	@Test
	public void regionKeyRoundTripsThroughFromRegion()
	{
		final WorldPoint original = new WorldPoint(3222, 3218, 2);
		final String key = BetterObjectHiderPlugin.regionKey(1276, original);

		final int[] parts = BetterObjectHiderPlugin.parseTileEntry(key);
		assertArrayEquals(
			new int[]{1276, original.getRegionID(), original.getRegionX(), original.getRegionY(), 2},
			parts);

		final WorldPoint rebuilt = WorldPoint.fromRegion(parts[1], parts[2], parts[3], parts[4]);
		assertEquals(original, rebuilt);
	}

	@Test
	public void parseTileEntryRejectsMalformedInput()
	{
		assertNull(BetterObjectHiderPlugin.parseTileEntry(""));
		assertNull(BetterObjectHiderPlugin.parseTileEntry("1276:3222:3218:0")); // old 4-field format
		assertNull(BetterObjectHiderPlugin.parseTileEntry("a:b:c:d:e"));
		assertNull(BetterObjectHiderPlugin.parseTileEntry("1:2:3:4:5:6"));
		assertNull(BetterObjectHiderPlugin.parseTileEntry("1276:-1:10:10:0"));
	}

	@Test
	public void parseTileEntryAcceptsValidInput()
	{
		assertArrayEquals(new int[]{1276, 12850, 22, 18, 0},
			BetterObjectHiderPlugin.parseTileEntry("1276:12850:22:18:0"));
	}

	@Test
	public void bannedObjectsAreRejected()
	{
		assertTrue(BetterObjectHiderPlugin.isBanned(ObjectID.TOB_BLOAT_PILLAR));
		assertTrue(BetterObjectHiderPlugin.isBanned(ObjectID.TOB_SOTETSEG_PLAINTILE));
		assertFalse(BetterObjectHiderPlugin.isBanned(1276)); // a regular tree
	}

	@Test
	public void displayNameFallsBackForUnnamedObjects()
	{
		assertEquals("Tree", BetterObjectHiderPlugin.displayName("Tree", 1276));
		assertEquals("Object #1276", BetterObjectHiderPlugin.displayName(null, 1276));
		assertEquals("Object #1276", BetterObjectHiderPlugin.displayName("", 1276));
		assertEquals("Object #1276", BetterObjectHiderPlugin.displayName("null", 1276));
	}

	// --- effective state ---------------------------------------------------------------

	@Test
	public void effectiveSetsExcludeDisabledGroups()
	{
		final HideGroup on = group("On", true);
		on.getIds().add(1276);
		on.getTiles().add("1276:12850:22:18:0");

		final HideGroup off = group("Off", false);
		off.getIds().add(9999);
		off.getTiles().add("9999:12850:23:19:0");

		final List<HideGroup> groups = List.of(on, off);
		assertEquals(Set.of(1276), BetterObjectHiderPlugin.effectiveIds(groups));
		assertEquals(Set.of("1276:12850:22:18:0"), BetterObjectHiderPlugin.effectiveTiles(groups));
	}

	// --- sanitization --------------------------------------------------------------------

	@Test
	public void sanitizeGroupHandlesNullAndGarbage()
	{
		assertNull(BetterObjectHiderPlugin.sanitizeGroup(null));

		final HideGroup dirty = new HideGroup();
		dirty.setName("  My group  ");
		dirty.setIds(null); // Gson can produce this from "ids": null
		dirty.setTiles(null);
		final HideGroup clean = BetterObjectHiderPlugin.sanitizeGroup(dirty);
		assertEquals("My group", clean.getName());
		assertTrue(clean.getIds().isEmpty());
		assertTrue(clean.getTiles().isEmpty());
	}

	@Test
	public void sanitizeGroupDropsInvalidAndBannedEntries()
	{
		final HideGroup dirty = new HideGroup();
		dirty.setName(null);
		final Set<Integer> ids = new HashSet<>();
		ids.add(1276);
		ids.add(-5);
		ids.add(null);
		ids.add(ObjectID.TOB_BLOAT_PILLAR); // banned
		dirty.setIds(ids);
		final Set<String> tiles = new HashSet<>();
		tiles.add("1276:12850:22:18:0");
		tiles.add("not a tile");
		tiles.add(null);
		tiles.add(ObjectID.TOB_SOTETSEG_PLAINTILE + ":12850:22:18:0"); // banned
		dirty.setTiles(tiles);

		final HideGroup clean = BetterObjectHiderPlugin.sanitizeGroup(dirty);
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, clean.getName());
		assertEquals(Set.of(1276), clean.getIds());
		assertEquals(Set.of("1276:12850:22:18:0"), clean.getTiles());
	}

	@Test
	public void sanitizeNameTrimsAndCaps()
	{
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, BetterObjectHiderPlugin.sanitizeName(null));
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, BetterObjectHiderPlugin.sanitizeName("   "));
		final String longName = "x".repeat(100);
		assertEquals(BetterObjectHiderPlugin.MAX_GROUP_NAME_LENGTH,
			BetterObjectHiderPlugin.sanitizeName(longName).length());
	}

	@Test
	public void uniqueNameAppendsCounterOnCollision()
	{
		final Set<String> taken = Set.of("Bank", "Bank (2)");
		assertEquals("Trees", BetterObjectHiderPlugin.uniqueName("Trees", taken));
		assertEquals("Bank (3)", BetterObjectHiderPlugin.uniqueName("Bank", taken));
	}

	// --- config parsing / migration -----------------------------------------------------

	@Test
	public void parseGroupsFallsBackOnMalformedJson()
	{
		for (String bad : new String[]{null, "", "not json at all {{{", "null", "[]"})
		{
			final List<HideGroup> groups = BetterObjectHiderPlugin.parseGroups(gson, bad);
			assertEquals("input: " + bad, 1, groups.size());
			assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, groups.get(0).getName());
			assertTrue(groups.get(0).getIds().isEmpty());
		}
	}

	@Test
	public void parseGroupsRoundTripsThroughGson()
	{
		final HideGroup group = group("Lumbridge", true);
		group.getIds().add(1276);
		group.getTiles().add("1276:12850:22:18:0");

		final String json = gson.toJson(List.of(group));
		final List<HideGroup> parsed = BetterObjectHiderPlugin.parseGroups(gson, json);
		assertEquals(1, parsed.size());
		assertEquals(group, parsed.get(0));
	}

	@Test
	public void parseGroupsDeduplicatesNames()
	{
		final String json = gson.toJson(List.of(group("Same", true), group("Same", false)));
		final List<HideGroup> parsed = BetterObjectHiderPlugin.parseGroups(gson, json);
		assertEquals(2, parsed.size());
		assertEquals("Same", parsed.get(0).getName());
		assertEquals("Same (2)", parsed.get(1).getName());
	}

	@Test
	public void migrateLegacyFoldsCsvIntoDefaultGroup()
	{
		final HideGroup migrated = BetterObjectHiderPlugin.migrateLegacy(
			"1276, 9999, junk, " + ObjectID.TOB_BLOAT_PILLAR,
			"1276:12850:22:18:0,bad entry,9999:12850:23:19:2");
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, migrated.getName());
		assertTrue(migrated.isEnabled());
		assertEquals(Set.of(1276, 9999), migrated.getIds());
		assertEquals(Set.of("1276:12850:22:18:0", "9999:12850:23:19:2"), migrated.getTiles());
	}

	@Test
	public void migrateLegacyHandlesEmptyInput()
	{
		final HideGroup migrated = BetterObjectHiderPlugin.migrateLegacy(null, null);
		assertTrue(migrated.getIds().isEmpty());
		assertTrue(migrated.getTiles().isEmpty());
	}

	// --- drag-and-drop moves --------------------------------------------------------------

	@Test
	public void moveIdTransfersBetweenGroups()
	{
		final HideGroup a = group("A", true);
		a.getIds().add(1276);
		final HideGroup b = group("B", true);
		final List<HideGroup> groups = List.of(a, b);

		assertTrue(BetterObjectHiderPlugin.moveId(groups, "A", "B", 1276));
		assertTrue(a.getIds().isEmpty());
		assertEquals(Set.of(1276), b.getIds());

		// Not present anymore / same group / unknown group → no-op
		assertFalse(BetterObjectHiderPlugin.moveId(groups, "A", "B", 1276));
		assertFalse(BetterObjectHiderPlugin.moveId(groups, "B", "B", 1276));
		assertFalse(BetterObjectHiderPlugin.moveId(groups, "B", "Nope", 1276));
		assertEquals(Set.of(1276), b.getIds());
	}

	@Test
	public void moveTileTransfersSingleEntry()
	{
		final HideGroup a = group("A", true);
		a.getTiles().add("1276:12850:22:18:0");
		a.getTiles().add("1276:12850:23:18:0");
		final HideGroup b = group("B", true);
		final List<HideGroup> groups = List.of(a, b);

		assertTrue(BetterObjectHiderPlugin.moveTile(groups, "A", "B", "1276:12850:22:18:0"));
		assertEquals(Set.of("1276:12850:23:18:0"), a.getTiles());
		assertEquals(Set.of("1276:12850:22:18:0"), b.getTiles());
	}

	@Test
	public void moveTilesOfIdTransfersOnlyMatchingEntries()
	{
		final HideGroup a = group("A", true);
		a.getTiles().add("1276:12850:22:18:0");
		a.getTiles().add("1276:12850:23:18:0");
		a.getTiles().add("9999:12850:24:18:0"); // different object, stays put
		final HideGroup b = group("B", true);
		final List<HideGroup> groups = List.of(a, b);

		assertTrue(BetterObjectHiderPlugin.moveTilesOfId(groups, "A", "B", 1276));
		assertEquals(Set.of("9999:12850:24:18:0"), a.getTiles());
		assertEquals(Set.of("1276:12850:22:18:0", "1276:12850:23:18:0"), b.getTiles());

		assertFalse(BetterObjectHiderPlugin.moveTilesOfId(groups, "A", "B", 1276));
	}

	// --- helpers -------------------------------------------------------------------------

	private static HideGroup group(String name, boolean enabled)
	{
		final HideGroup group = new HideGroup();
		group.setName(name);
		group.setEnabled(enabled);
		return group;
	}
}
