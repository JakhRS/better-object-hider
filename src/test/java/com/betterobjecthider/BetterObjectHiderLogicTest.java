/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BetterObjectHiderLogicTest
{
	private final Gson gson = new Gson();

	// --- name resolution ---------------------------------------------------------------

	@Test
	public void cleanNameRejectsUnnamedSentinels()
	{
		assertEquals("Tree", BetterObjectHiderPlugin.cleanName("Tree"));
		assertEquals("Bank booth", BetterObjectHiderPlugin.cleanName("  Bank booth  "));
		assertNull(BetterObjectHiderPlugin.cleanName(null));
		assertNull(BetterObjectHiderPlugin.cleanName(""));
		assertNull(BetterObjectHiderPlugin.cleanName("null"));
	}

	@Test
	public void cleanNameStripsMarkup()
	{
		// Untrusted imported names render at the start of a Swing JLabel
		final String cleaned = BetterObjectHiderPlugin.cleanName("<html><img src=http://evil/x>");
		assertFalse(cleaned.contains("<"));
		assertFalse(cleaned.contains(">"));
	}

	// --- render match key ---------------------------------------------------------------

	@Test
	public void tileKeyEncodesWorldPoint()
	{
		final WorldPoint wp = new WorldPoint(3222, 3218, 0);
		assertEquals("1276:3222:3218:0", BetterObjectHiderPlugin.tileKey(1276, wp));
	}

	// --- entry matching -----------------------------------------------------------------

	@Test
	public void tileEntryMatchesExactLocationAndScope()
	{
		final HideEntry e = tileEntry("Tree", 12850, 22, 18, 0, false);
		assertTrue(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 22, 18, 0, false));
		// wrong tile
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 23, 18, 0, false));
		// wrong name
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Oak", 12850, 22, 18, 0, false));
		// wrong scope (instance vs overworld)
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 22, 18, 0, true));
	}

	@Test
	public void areaEntryMatchesAnyTileInRegion()
	{
		final HideEntry e = areaEntry("Tree", 12850, false);
		assertTrue(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 5, 60, 0, false));
		assertTrue(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 40, 1, 2, false));
		// different region
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12851, 5, 60, 0, false));
		// wrong name
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Oak", 12850, 5, 60, 0, false));
	}

	@Test
	public void globalEntryMatchesNameAnywhereAndAnyScope()
	{
		final HideEntry e = new HideEntry();
		e.setObjectName("Tree");
		e.setScope(HideScope.GLOBAL);
		// any region, any tile, instance or overworld
		assertTrue(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 12850, 5, 60, 0, false));
		assertTrue(BetterObjectHiderPlugin.matchesEntry(e, "Tree", 9999, 1, 1, 3, true));
		// still name-scoped
		assertFalse(BetterObjectHiderPlugin.matchesEntry(e, "Oak", 12850, 5, 60, 0, false));
	}

	// --- effective state ----------------------------------------------------------------

	@Test
	public void effectiveEntriesExcludeDisabledGroups()
	{
		final HideGroup on = group("On", true);
		on.getEntries().add(tileEntry("Tree", 12850, 22, 18, 0, false));

		final HideGroup off = group("Off", false);
		off.getEntries().add(tileEntry("Oak", 12850, 23, 18, 0, false));

		final Set<HideEntry> effective = BetterObjectHiderPlugin.effectiveEntries(List.of(on, off));
		assertEquals(1, effective.size());
		assertEquals(Set.of("Tree"), BetterObjectHiderPlugin.entryNames(effective));
	}

	// --- sanitization -------------------------------------------------------------------

	@Test
	public void sanitizeGroupDropsUnnamedAndBanned()
	{
		final HideGroup dirty = group("Mine", true);
		dirty.getEntries().add(tileEntry("Tree", 12850, 22, 18, 0, false));
		dirty.getEntries().add(tileEntry("null", 12850, 23, 18, 0, false));   // sentinel
		dirty.getEntries().add(tileEntry("", 12850, 24, 18, 0, false));       // empty
		dirty.getEntries().add(tileEntry("Bloat pillar", 12850, 25, 18, 0, false)); // banned

		final HideGroup clean = BetterObjectHiderPlugin.sanitizeGroup(dirty, Set.of("Bloat pillar"));
		assertEquals(1, clean.getEntries().size());
		assertEquals("Tree", clean.getEntries().iterator().next().getObjectName());
	}

	@Test
	public void sanitizeGroupHandlesNull()
	{
		assertNull(BetterObjectHiderPlugin.sanitizeGroup(null, Set.of()));
	}

	@Test
	public void sanitizeNameTrimsCapsAndStripsMarkup()
	{
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, BetterObjectHiderPlugin.sanitizeName(null));
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, BetterObjectHiderPlugin.sanitizeName("   "));
		assertEquals("Trees", BetterObjectHiderPlugin.sanitizeName("Tr<ee>s"));
		assertEquals(BetterObjectHiderPlugin.MAX_GROUP_NAME_LENGTH,
			BetterObjectHiderPlugin.sanitizeName("x".repeat(100)).length());
	}

	@Test
	public void uniqueNameAppendsCounterOnCollision()
	{
		assertEquals("Trees", BetterObjectHiderPlugin.uniqueName("Trees", Set.of("Bank", "Bank (2)")));
		assertEquals("Bank (3)", BetterObjectHiderPlugin.uniqueName("Bank", Set.of("Bank", "Bank (2)")));
	}

	// --- Default group safeguard --------------------------------------------------------

	@Test
	public void defaultGroupIsRecreatedWhenMissing()
	{
		final List<HideGroup> groups = new ArrayList<>(List.of(group("POH", true)));
		BetterObjectHiderPlugin.ensureDefaultGroup(groups);
		assertEquals(2, groups.size());
		assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, groups.get(0).getName());

		BetterObjectHiderPlugin.ensureDefaultGroup(groups);
		assertEquals(2, groups.size());
	}

	// --- drag-and-drop move -------------------------------------------------------------

	@Test
	public void moveEntryTransfersBetweenGroups()
	{
		final HideEntry e = tileEntry("Tree", 12850, 22, 18, 0, false);
		final HideGroup a = group("A", true);
		a.getEntries().add(e);
		final HideGroup b = group("B", true);
		final List<HideGroup> groups = List.of(a, b);

		assertTrue(BetterObjectHiderPlugin.moveEntry(groups, "A", "B", e));
		assertTrue(a.getEntries().isEmpty());
		assertEquals(Set.of(e), b.getEntries());
		// already moved / same group
		assertFalse(BetterObjectHiderPlugin.moveEntry(groups, "A", "B", e));
		assertFalse(BetterObjectHiderPlugin.moveEntry(groups, "B", "B", e));
	}

	// --- config parsing -----------------------------------------------------------------

	@Test
	public void parseGroupsFallsBackOnMalformedJson()
	{
		for (String bad : new String[]{null, "", "not json {{{", "null", "[]"})
		{
			final List<HideGroup> groups = BetterObjectHiderPlugin.parseGroups(gson, bad, Set.of());
			assertEquals("input: " + bad, 1, groups.size());
			assertEquals(BetterObjectHiderPlugin.DEFAULT_GROUP_NAME, groups.get(0).getName());
			assertTrue(groups.get(0).getEntries().isEmpty());
		}
	}

	@Test
	public void parseGroupsRoundTripsNameBasedEntries()
	{
		final HideGroup g = group("Lumbridge", true);
		g.getEntries().add(tileEntry("Tree", 12850, 22, 18, 0, false));
		g.getEntries().add(areaEntry("Rock", 12851, true));

		final String json = gson.toJson(List.of(g));
		final List<HideGroup> parsed = BetterObjectHiderPlugin.parseGroups(gson, json, Set.of());
		assertEquals(1, parsed.size());
		assertEquals(g.getEntries(), parsed.get(0).getEntries());
	}

	// --- active-group timeout -----------------------------------------------------------

	@Test
	public void activeGroupExpiryRespectsTimeout()
	{
		final long now = 1_000_000_000_000L;
		final long minute = 60_000L;
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 59 * minute), now, 60));
		assertTrue(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 61 * minute), now, 60));
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(String.valueOf(now - 999 * minute), now, 0));
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired("garbage", now, 60));
		assertFalse(BetterObjectHiderPlugin.isActiveGroupExpired(null, now, 60));
	}

	// --- helpers ------------------------------------------------------------------------

	private static HideGroup group(String name, boolean enabled)
	{
		final HideGroup group = new HideGroup();
		group.setName(name);
		group.setEnabled(enabled);
		return group;
	}

	private static HideEntry tileEntry(String name, int regionId, int rx, int ry, int plane, boolean instance)
	{
		final HideEntry e = new HideEntry();
		e.setObjectName(name);
		e.setScope(HideScope.TILE);
		e.setRegionId(regionId);
		e.setRegionX(rx);
		e.setRegionY(ry);
		e.setPlane(plane);
		e.setInstance(instance);
		return e;
	}

	private static HideEntry areaEntry(String name, int regionId, boolean instance)
	{
		final HideEntry e = new HideEntry();
		e.setObjectName(name);
		e.setScope(HideScope.AREA);
		e.setRegionId(regionId);
		e.setInstance(instance);
		return e;
	}
}
