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
import static org.junit.Assert.assertNotEquals;
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
	public void tileKeyIsStableAndCollisionFree()
	{
		final WorldPoint wp = new WorldPoint(3222, 3218, 0);
		assertEquals(BetterObjectHiderPlugin.tileKey(1276, wp), BetterObjectHiderPlugin.tileKey(1276, wp));

		// Any differing component must produce a different key
		final long base = BetterObjectHiderPlugin.tileKey(1276, wp);
		assertNotEquals(base, BetterObjectHiderPlugin.tileKey(1277, wp));
		assertNotEquals(base, BetterObjectHiderPlugin.tileKey(1276, new WorldPoint(3223, 3218, 0)));
		assertNotEquals(base, BetterObjectHiderPlugin.tileKey(1276, new WorldPoint(3222, 3219, 0)));
		assertNotEquals(base, BetterObjectHiderPlugin.tileKey(1276, new WorldPoint(3222, 3218, 1)));
		// Adjacent-coordinate cross-talk (x borrowing into y's bits, etc.)
		assertNotEquals(
			BetterObjectHiderPlugin.tileKey(1276, new WorldPoint(3223, 3218, 0)),
			BetterObjectHiderPlugin.tileKey(1276, new WorldPoint(3222, 3218 + (1 << 15), 0)));
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

	// --- entry normalization --------------------------------------------------------------

	@Test
	public void sanitizeGroupNormalizesScopeIrrelevantFields()
	{
		// An imported GLOBAL/AREA entry with junk in unused fields must compare
		// equal to the menu-built form, or unhide/de-dupe break silently
		final HideEntry junkGlobal = new HideEntry();
		junkGlobal.setObjectName("Tree");
		junkGlobal.setScope(HideScope.GLOBAL);
		junkGlobal.setRegionId(123);
		junkGlobal.setRegionX(45);
		junkGlobal.setRegionY(46);
		junkGlobal.setPlane(2);
		junkGlobal.setInstance(true);

		final HideEntry junkArea = areaEntry("Rock", 12850, true);
		junkArea.setRegionX(9);
		junkArea.setRegionY(9);
		junkArea.setPlane(3);

		final HideGroup dirty = group("Mine", true);
		dirty.getEntries().add(junkGlobal);
		dirty.getEntries().add(junkArea);

		final HideGroup clean = BetterObjectHiderPlugin.sanitizeGroup(dirty, Set.of());

		final HideEntry expectedGlobal = new HideEntry();
		expectedGlobal.setObjectName("Tree");
		expectedGlobal.setScope(HideScope.GLOBAL);
		final HideEntry expectedArea = areaEntry("Rock", 12850, true);
		assertEquals(Set.of(expectedGlobal, expectedArea), clean.getEntries());
	}

	@Test
	public void normalizeEntryRejectsOutOfRangeTiles()
	{
		assertNull(BetterObjectHiderPlugin.normalizeEntry("Tree", tileEntry("Tree", -1, 0, 0, 0, false)));
		assertNull(BetterObjectHiderPlugin.normalizeEntry("Tree", tileEntry("Tree", 12850, 64, 0, 0, false)));
		assertNull(BetterObjectHiderPlugin.normalizeEntry("Tree", tileEntry("Tree", 12850, 0, -1, 0, false)));
		assertNull(BetterObjectHiderPlugin.normalizeEntry("Tree", tileEntry("Tree", 12850, 0, 0, 4, false)));
		assertEquals(tileEntry("Tree", 12850, 63, 63, 3, true),
			BetterObjectHiderPlugin.normalizeEntry("Tree", tileEntry("Tree", 12850, 63, 63, 3, true)));
	}

	// --- search filter --------------------------------------------------------------------

	@Test
	public void filterEmptyQueryMatchesEverything()
	{
		assertTrue(EntryFilter.parse(null).isEmpty());
		assertTrue(EntryFilter.parse("   ").isEmpty());
		assertTrue(EntryFilter.parse("").matches(tileEntry("Tree", 12850, 1, 1, 0, false), "Default", null));
	}

	@Test
	public void filterPlainTermMatchesObjectName()
	{
		final EntryFilter filter = EntryFilter.parse("tree");
		assertTrue(filter.matches(tileEntry("Yew tree", 12850, 1, 1, 0, false), "Default", null));
		assertFalse(filter.matches(tileEntry("Rock", 12850, 1, 1, 0, false), "Default", null));
	}

	@Test
	public void filterWildcardMatchesWholeValueOrAnyWord()
	{
		assertTrue(EntryFilter.matchesValue("bank*", "Bank booth"));
		assertTrue(EntryFilter.matchesValue("*booth", "Bank booth"));
		assertTrue(EntryFilter.matchesValue("b*th", "Bank booth"));
		// Word-level: the pattern anchors to each word, not just the whole name
		assertTrue(EntryFilter.matchesValue("booth*", "Bank booth"));
		assertTrue(EntryFilter.matchesValue("tree*", "Darkwood Tree"));
		assertTrue(EntryFilter.matchesValue("tree*", "Trees"));
		// ...but still anchored: no word of "Darkwood Tree" starts with "wood"
		assertFalse(EntryFilter.matchesValue("wood*", "Darkwood Tree"));
		// Regex metacharacters in the term are literal
		assertFalse(EntryFilter.matchesValue("b.*h", "Bank booth"));
		assertFalse(EntryFilter.matchesValue("tree*", null));
	}

	@Test
	public void filterAreaTermMatchesLocationLabel()
	{
		final EntryFilter filter = EntryFilter.parse("area:kandarin");
		final HideEntry tile = tileEntry("Tree", 9520, 30, 30, 0, false);
		assertTrue(filter.matches(tile, "Default", "Castle Wars (Kandarin)"));
		assertFalse(filter.matches(tile, "Default", "Lumbridge (Misthalin)"));
		assertFalse(filter.matches(tile, "Default", null));

		// Global hides apply everywhere, so they match any area term
		final HideEntry global = new HideEntry();
		global.setObjectName("Tree");
		global.setScope(HideScope.GLOBAL);
		assertTrue(filter.matches(global, "Default", null));
	}

	@Test
	public void filterGroupScopeAndInstanceTerms()
	{
		final HideEntry instanced = areaEntry("Rock", 12850, true);
		assertTrue(EntryFilter.parse("group:pvp").matches(instanced, "PvP worlds", null));
		assertFalse(EntryFilter.parse("group:pvp").matches(instanced, "Default", null));
		assertTrue(EntryFilter.parse("scope:area").matches(instanced, "Default", null));
		assertFalse(EntryFilter.parse("scope:tile").matches(instanced, "Default", null));
		assertTrue(EntryFilter.parse("is:instance").matches(instanced, "Default", null));
		assertFalse(EntryFilter.parse("is:instance").matches(areaEntry("Rock", 12850, false), "Default", null));

		final HideEntry global = new HideEntry();
		global.setObjectName("Tree");
		global.setScope(HideScope.GLOBAL);
		assertTrue(EntryFilter.parse("scope:everywhere").matches(global, "Default", null));
		assertTrue(EntryFilter.parse("scope:global").matches(global, "Default", null));
	}

	@Test
	public void filterTermsAreAnded()
	{
		final EntryFilter filter = EntryFilter.parse("tree area:kandarin");
		final HideEntry tile = tileEntry("Yew tree", 9520, 30, 30, 0, false);
		assertTrue(filter.matches(tile, "Default", "Castle Wars (Kandarin)"));
		assertFalse(filter.matches(tile, "Default", "Lumbridge (Misthalin)"));
		assertFalse(filter.matches(tileEntry("Rock", 9520, 30, 30, 0, false), "Default", "Castle Wars (Kandarin)"));
	}

	@Test
	public void filterUnknownPrefixIsAPlainNameTerm()
	{
		assertFalse(EntryFilter.parse("foo:bar").matches(tileEntry("Tree", 12850, 1, 1, 0, false), "Default", null));
	}

	// --- coordinate boxes -------------------------------------------------------------------

	@Test
	public void coordBoxParseToleratesJunk()
	{
		assertNull(CoordBoxes.parseLine(null));
		assertNull(CoordBoxes.parseLine(""));
		assertNull(CoordBoxes.parseLine("# comment"));
		assertNull(CoordBoxes.parseLine("1\t2\t3"));
		assertNull(CoordBoxes.parseLine("a\t2\t3\t4\tName"));
		assertNull(CoordBoxes.parseLine("5\t2\t3\t4\tName")); // minX > maxX
		assertNull(CoordBoxes.parseLine("1\t2\t3\t4\t "));

		final CoordBoxes.Box box = CoordBoxes.parseLine("1\t2\t3\t4\tName");
		assertEquals("Name", box.name);
	}

	@Test
	public void coordBoxLookupIsInclusiveAndFirstMatchWins()
	{
		final List<CoordBoxes.Box> boxes = List.of(
			CoordBoxes.parseLine("10\t10\t20\t20\tInner"),
			CoordBoxes.parseLine("0\t0\t30\t30\tOuter"));
		assertEquals("Inner", CoordBoxes.lookup(boxes, 10, 10)); // min edge
		assertEquals("Inner", CoordBoxes.lookup(boxes, 20, 20)); // max edge
		assertEquals("Inner", CoordBoxes.lookup(boxes, 15, 15));
		assertEquals("Outer", CoordBoxes.lookup(boxes, 9, 15));
		assertNull(CoordBoxes.lookup(boxes, 31, 15));
	}

	// --- world map labels -----------------------------------------------------------------

	@Test
	public void mapLabelParseToleratesJunk()
	{
		assertNull(MapLabels.parseLine(null));
		assertNull(MapLabels.parseLine(""));
		assertNull(MapLabels.parseLine("# comment"));
		assertNull(MapLabels.parseLine("1\t2\t3"));
		assertNull(MapLabels.parseLine("a\t2\t3\tName"));
		assertNull(MapLabels.parseLine("1\t2\t3\t "));
		assertEquals("Name", MapLabels.parseLine("1\t2\t3\tName").name);
	}

	@Test
	public void mapLabelNearestPicksClosestWithinRadius()
	{
		final List<MapLabels.Label> labels = List.of(
			MapLabels.parseLine("100\t100\t0\tNear"),
			MapLabels.parseLine("130\t100\t0\tFar"));
		assertEquals("Near", MapLabels.nearest(labels, 105, 103, 24));
		assertEquals("Far", MapLabels.nearest(labels, 126, 100, 24));
		// Chebyshev distance: (100,100) -> (124,124) is 24, inside; 25 is out
		assertEquals("Near", MapLabels.nearest(labels, 124, 124, 24));
		assertNull(MapLabels.nearest(labels, 100, 150, 24));
		assertNull(MapLabels.nearest(List.of(), 100, 100, 24));
	}

	@Test
	public void mapLabelDataLoadsAndIsSelfConsistent()
	{
		// The generated file should be substantial and usable at its own coords
		assertTrue("expected many labels, got " + MapLabels.labels().size(),
			MapLabels.labels().size() > 500);
		final MapLabels.Label first = MapLabels.labels().get(0);
		assertEquals(first.name, MapLabels.nearest(first.x, first.y, 0));
	}

	// --- location labels ----------------------------------------------------------------------

	@Test
	public void locationLabelCombinesPlaceAndProvince()
	{
		assertEquals("Castle Wars (Kandarin)", LocationLabel.combine("Castle Wars", "Kandarin"));
		assertEquals("Castle Wars", LocationLabel.combine("Castle Wars", null));
		assertEquals("Kandarin", LocationLabel.combine(null, "Kandarin"));
		assertEquals("Kandarin", LocationLabel.combine("Kandarin", "Kandarin"));
		assertNull(LocationLabel.combine(null, null));
	}

	@Test
	public void locationLabelDescribesKnownPlaces()
	{
		// Castle Wars arena region 9520 and lobby region 9776 (which the upstream
		// region table lumps into "Kandarin") — the curated places box wins
		assertEquals("Castle Wars (Kandarin)",
			LocationLabel.describe(tileEntry("Tree", 9520, 30, 30, 0, false)));
		assertEquals("Castle Wars (Kandarin)",
			LocationLabel.describe(tileEntry("Bank chest", 9776, 8, 18, 0, false)));
		// Instanced template regions live off the overworld: place layer only
		assertEquals("Player-owned House",
			LocationLabel.describe(tileEntry("Chair", 7769, 10, 10, 0, true)));
		assertEquals("All of Lumbridge (Misthalin)",
			LocationLabel.describe(areaEntry("Tree", 12850, false)));
		// Goblin Village sits in region 11830, which the region table only knows
		// as "Asgarnia" — the world-map label layer supplies the real place.
		// (World coords 2963,3502 → region 11830, regionX 19, regionY 46.)
		assertEquals("Goblin Village (Asgarnia)",
			LocationLabel.describe(tileEntry("Tent", 11830, 19, 46, 0, false)));
	}

	@Test
	public void locationLabelUsesDungeonNameAsUndergroundContext()
	{
		// Underground points have no province, so the context falls back to the
		// region table. "Blue dragons" label at (2908,9807) → region 11673,
		// regionX 28, regionY 15 — Taverley Dungeon.
		assertEquals("Blue dragons (Taverley Dungeon)",
			LocationLabel.describe(tileEntry("Rocks", 11673, 28, 15, 0, false)));
	}

	@Test
	public void locationLabelNamesTheMaggotKingLair()
	{
		// Real user data: an instance-scoped AREA hide made in a pre-labels
		// version of the plugin (template region 11645, off-map, unlabeled) —
		// the curated places box must name it retroactively.
		final HideEntry entry = areaEntry("Darkwood tree", 11645, true);
		assertEquals("All of Maggot King's lair", LocationLabel.describe(entry));
	}

	@Test
	public void locationLabelFallsBackToCoordinates()
	{
		assertEquals("Tile 69, 70", LocationLabel.describe(tileEntry("Tree", 257, 5, 6, 0, false)));
		assertEquals("Tile 69, 70, plane 2", LocationLabel.describe(tileEntry("Tree", 257, 5, 6, 2, false)));
		assertEquals("This whole area (near 64, 64)", LocationLabel.describe(areaEntry("Tree", 257, false)));

		final HideEntry global = new HideEntry();
		global.setObjectName("Tree");
		global.setScope(HideScope.GLOBAL);
		assertEquals("Everywhere", LocationLabel.describe(global));
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
