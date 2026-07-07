/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import com.google.gson.Gson;
import java.util.ArrayList;
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
		on.getAreas().add("1276:12850");

		final HideGroup off = group("Off", false);
		off.getIds().add(9999);
		off.getTiles().add("9999:12850:23:19:0");
		off.getAreas().add("9999:12850");

		final List<HideGroup> groups = List.of(on, off);
		assertEquals(Set.of(1276), BetterObjectHiderPlugin.effectiveIds(groups));
		assertEquals(Set.of("1276:12850:22:18:0"), BetterObjectHiderPlugin.effectiveTiles(groups));
		assertEquals(Set.of("1276:12850"), BetterObjectHiderPlugin.effectiveAreas(groups));
	}

	// --- area entries -------------------------------------------------------------------

	@Test
	public void parseAreaEntryValidatesFormat()
	{
		assertArrayEquals(new int[]{1276, 12850}, BetterObjectHiderPlugin.parseAreaEntry("1276:12850"));
		assertArrayEquals(new int[]{1276, 12850}, BetterObjectHiderPlugin.parseAreaEntry("1276:12850:i"));
		assertNull(BetterObjectHiderPlugin.parseAreaEntry(""));
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("1276"));
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("1276:12850:0")); // tile format, not area
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("1276:12850:x")); // bad scope suffix
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("a:b"));
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("0:12850"));  // id must be positive
		assertNull(BetterObjectHiderPlugin.parseAreaEntry("1276:-1"));
	}

	@Test
	public void instanceScopeSuffixIsParsedAndDetected()
	{
		// Tile entries accept the ":i" instance tag
		assertArrayEquals(new int[]{1276, 12850, 22, 18, 0},
			BetterObjectHiderPlugin.parseTileEntry("1276:12850:22:18:0:i"));
		assertNull(BetterObjectHiderPlugin.parseTileEntry("1276:12850:22:18:0:x"));
		assertNull(BetterObjectHiderPlugin.parseTileEntry("1276:12850:22:18:0:i:i"));

		assertTrue(BetterObjectHiderPlugin.isInstanceEntry("1276:12850:22:18:0:i"));
		assertTrue(BetterObjectHiderPlugin.isInstanceEntry("1276:12850:i"));
		assertFalse(BetterObjectHiderPlugin.isInstanceEntry("1276:12850:22:18:0"));
		assertFalse(BetterObjectHiderPlugin.isInstanceEntry("1276:12850"));

		assertEquals("1276:12850:i", BetterObjectHiderPlugin.scoped("1276:12850", true));
		assertEquals("1276:12850", BetterObjectHiderPlugin.scoped("1276:12850", false));
	}

	@Test
	public void scopedEntriesOnlyMatchTheirOwnViewUnlessRestoring()
	{
		final String area = "11850:9551";
		final Set<String> instanceEntries = Set.of("11850:9551:i");
		final Set<String> overworldEntries = Set.of("11850:9551");

		assertTrue(BetterObjectHiderPlugin.containsScopedEntry(instanceEntries, area, true, false));
		assertFalse(BetterObjectHiderPlugin.containsScopedEntry(instanceEntries, area, false, false));
		assertTrue(BetterObjectHiderPlugin.containsScopedEntry(instanceEntries, area, false, true));

		assertTrue(BetterObjectHiderPlugin.containsScopedEntry(overworldEntries, area, false, false));
		assertFalse(BetterObjectHiderPlugin.containsScopedEntry(overworldEntries, area, true, false));
		assertTrue(BetterObjectHiderPlugin.containsScopedEntry(overworldEntries, area, true, true));
	}

	@Test
	public void sanitizeGroupFiltersAreaEntries()
	{
		final HideGroup dirty = group("Areas", true);
		dirty.getAreas().add("1276:12850");
		dirty.getAreas().add("garbage");
		dirty.getAreas().add(ObjectID.TOB_BLOAT_PILLAR + ":12850"); // banned

		final HideGroup clean = BetterObjectHiderPlugin.sanitizeGroup(dirty);
		assertEquals(Set.of("1276:12850"), clean.getAreas());
	}

	@Test
	public void moveAreaTransfersBetweenGroups()
	{
		final HideGroup a = group("A", true);
		a.getAreas().add("1276:12850");
		final HideGroup b = group("B", true);
		final List<HideGroup> groups = List.of(a, b);

		assertTrue(BetterObjectHiderPlugin.moveArea(groups, "A", "B", "1276:12850"));
		assertTrue(a.getAreas().isEmpty());
		assertEquals(Set.of("1276:12850"), b.getAreas());
		assertFalse(BetterObjectHiderPlugin.moveArea(groups, "A", "B", "1276:12850"));
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
	public void sanitizeNameStripsMarkup()
	{
		// A leading "<html>" in a JLabel triggers Swing markup rendering (remote <img> fetch);
		// angle brackets must never survive into a displayed name.
		final String stripped = BetterObjectHiderPlugin.sanitizeName("<html><img src=http://evil/x>");
		assertFalse(stripped.contains("<"));
		assertFalse(stripped.contains(">"));
		assertEquals("Trees", BetterObjectHiderPlugin.sanitizeName("Tr<ee>s"));
		// A name that is ONLY markup collapses to the default, never empty
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME,
			BetterObjectHiderPlugin.sanitizeName("<<>>"));
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

	// --- Default group safeguard ----------------------------------------------------------

	@Test
	public void defaultGroupIsRecreatedWhenMissing()
	{
		final List<HideGroup> groups = new ArrayList<>(List.of(group("POH", true)));
		BetterObjectHiderPlugin.ensureDefaultGroup(groups);
		assertEquals(2, groups.size());
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, groups.get(0).getName());

		// Idempotent: never duplicates an existing Default
		BetterObjectHiderPlugin.ensureDefaultGroup(groups);
		assertEquals(2, groups.size());
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

	// --- active-group timeout --------------------------------------------------------------

	@Test
	public void activeGroupExpiryRespectsTimeout()
	{
		final long now = 1_000_000_000_000L;
		final long minute = 60_000L;

		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 59 * minute), now, 60));
		assertTrue(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 61 * minute), now, 60));
		// 0 = disabled, even for ancient timestamps
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 999_999 * minute), now, 0));
		// Corrupt/missing values never trigger a reset
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired("garbage", now, 60));
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(null, now, 60));
	}

	@Test
	public void expiryTargetPrefersDefaultThenFirst()
	{
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, BetterObjectHiderPlugin.expiryTarget(
			List.of(group("POH", true), group(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, true))));
		assertEquals("POH", BetterObjectHiderPlugin.expiryTarget(
			List.of(group("POH", true), group("GE", true))));
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME,
			BetterObjectHiderPlugin.expiryTarget(List.of()));
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
