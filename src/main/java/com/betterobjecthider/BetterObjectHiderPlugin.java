/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 *
 * Inspired by LuxOG's custom-object-hider (BSD-2-Clause).
 * This plugin is purely cosmetic: it changes only what is rendered
 * client-side. It sends nothing to the server and automates nothing.
 */
package com.betterobjecthider;

import com.betterobjecthider.ui.BetterObjectHiderPanel;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Better Object Hider",
	description = "Hide individual game objects on specific tiles, or all objects sharing an ID, organized into shareable groups",
	tags = {"hide", "object", "declutter", "qol", "group"}
)
public class BetterObjectHiderPlugin extends Plugin implements RenderCallback
{
	static final String CONFIG_GROUP = "betterobjecthider";
	public static final String DEFAULT_GROUP_NAME = "Default";
	static final int MAX_GROUP_NAME_LENGTH = 40;

	// Options are color-coded by scope as a traffic-light ladder: green for the
	// flagship single-object hide (the safest and most-used option, so it gets
	// the most identifiable color), yellow for one map area, red for everywhere.
	// Unhide options mirror the same ladder.
	private static final String HIDE_ONE = "<col=00ff7f>Hide this one</col>";
	private static final String HIDE_AREA = "<col=ffd166>Hide all of ID in area</col>";
	private static final String HIDE_ID = "<col=ff6b6b>Hide all of ID</col>";
	private static final String UNHIDE_ONE = "<col=00ff7f>Unhide this one</col>";
	private static final String UNHIDE_AREA = "<col=ffd166>Unhide all of ID in area</col>";
	private static final String UNHIDE_ID = "<col=ff6b6b>Unhide all of ID</col>";

	// Hiding these would suppress boss mechanics (RULES.md: "Sotetseg maze reveal"
	// is explicitly prohibited); same list as LuxOG's custom-object-hider.
	private static final Set<Integer> BANNED_OBJECTS = Set.of(
		ObjectID.TOB_BLOAT_PILLAR,
		ObjectID.TOB_BLOAT_CHAMBER,
		ObjectID.TOB_SOTETSEG_PLAINTILE,
		ObjectID.TOB_SOTETSEG_DARKTILE,
		ObjectID.TOB_SOTETSEG_LIGHTTILE,
		ObjectID.TOB_SOTETSEG_TRIGGEREDTILE,
		ObjectID.TOB_SOTETSEG_HARD_DARKTILE,
		ObjectID.TOB_SOTETSEG_HARD_LIGHTTILE,
		ObjectID.TOB_SOTETSEG_HARD_LIGHTTILE_PARENT_1,
		ObjectID.TOB_SOTETSEG_HARD_LIGHTTILE_PARENT_2,
		ObjectID.TOB_SOTETSEG_HARD_LIGHTTILE_PARENT_3,
		ObjectID.TOB_SOTETSEG_HARD_LIGHTTILE_PARENT_4
	);

	/**
	 * Zone array index offset: (EXTENDED_SCENE_SIZE - SCENE_SIZE) / 2 / 8
	 * EXTENDED = 184, SCENE = 104 → offset = (184-104)/2 = 40 → 40>>3 = 5
	 */
	private static final int ZONE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 >> 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private BetterObjectHiderConfig config;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	// Persisted model. Mutated only inside synchronized(this) blocks — mutations
	// arrive from both the client thread (menu clicks) and the EDT (panel buttons).
	private final List<HideGroup> groups = new ArrayList<>();
	private String activeGroupName = DEFAULT_GROUP_NAME;

	// Derived effective state, the ONLY sets drawObject() reads. Concurrent:
	// drawObject() is called from the maploader thread during scene upload.
	private final Set<Integer> activeHiddenIds = ConcurrentHashMap.newKeySet();
	// Scene-space "id:x:y:plane" lookup, recomputed per world view (never persisted)
	private final Set<String> activeHiddenTilePoints = ConcurrentHashMap.newKeySet();
	// Template-space "id:regionId" pairs from enabled groups (region hides need no
	// re-projection — the object's template region is resolved at draw time)
	private final Set<String> activeAreaPairs = ConcurrentHashMap.newKeySet();
	// Object names resolved on the client thread, read by the panel on the EDT
	private final Map<Integer, String> objectNames = new ConcurrentHashMap<>();

	private BetterObjectHiderPanel pluginPanel;
	private NavigationButton navigationButton;
	private volatile Boolean lastTopLevelInstance;
	private volatile boolean pendingSceneScopeRestore;

	@Provides
	BetterObjectHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterObjectHiderConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateLegacyConfig();
		loadFromConfig();
		renderCallbackManager.register(this);

		pluginPanel = new BetterObjectHiderPanel(this);
		pluginPanel.rebuild();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navigationButton = NavigationButton.builder()
			.tooltip("Better Object Hider")
			.icon(icon)
			.priority(5)
			.panel(pluginPanel)
			.build();
		clientToolbar.addNavigation(navigationButton);

		clientThread.invokeLater(this::refresh);
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(this);
		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		pluginPanel = null;

		// Capture what was hidden before clearing so we can restore those zones
		final Set<Integer> ids = new HashSet<>(activeHiddenIds);
		final Set<String> points = new HashSet<>(activeHiddenTilePoints);
		final Set<String> areas = new HashSet<>(activeAreaPairs);
		synchronized (this)
		{
			groups.clear();
		}
		activeHiddenIds.clear();
		activeHiddenTilePoints.clear();
		activeAreaPairs.clear();
		clientThread.invokeLater(() -> invalidateZones(ids, points, areas, true));
	}

	// --- RenderCallback ----------------------------------------------------------

	@Override
	public boolean drawObject(Scene scene, TileObject object)
	{
		if (!isHidden(scene, object))
		{
			return true;
		}
		// In reveal mode hidden objects stay visible so they can be unhidden
		return config.revealAll();
	}

	private boolean isHidden(TileObject object)
	{
		return isHidden(sceneOf(object), object);
	}

	private boolean isHidden(Scene scene, TileObject object)
	{
		final int id = object.getId();
		if (activeHiddenIds.contains(id))
		{
			return true;
		}
		return matchesTile(activeHiddenTilePoints, scene, object)
			|| matchesArea(activeAreaPairs, scene, object);
	}

	/**
	 * Render callbacks receive the scene currently being uploaded; during instance
	 * transitions that is the safest source of scope. The object's world view can
	 * briefly lag the upload, which is how Fight Caves template tiles were baked
	 * into Mor Ul Rek until another hider plugin forced a rebuild.
	 */
	private static boolean matchesTile(Set<String> tilePoints, Scene scene, TileObject object)
	{
		return matchesTile(tilePoints, scene, object, false);
	}

	private static boolean matchesTile(Set<String> tilePoints, Scene scene, TileObject object, boolean ignoreScope)
	{
		return !tilePoints.isEmpty()
			&& containsScopedEntry(tilePoints, tileKey(object.getId(), object.getWorldLocation()),
				scopeIsInstance(scene, object), ignoreScope);
	}

	private static boolean matchesArea(Set<String> areaPairs, Scene scene, TileObject object)
	{
		return matchesArea(areaPairs, scene, object, false);
	}

	private static boolean matchesArea(Set<String> areaPairs, Scene scene, TileObject object, boolean ignoreScope)
	{
		if (areaPairs.isEmpty())
		{
			return false;
		}
		final int regionId = templateRegionOf(scene, object);
		return regionId != -1
			&& containsScopedEntry(areaPairs, areaKey(object.getId(), regionId),
				scopeIsInstance(scene, object), ignoreScope);
	}

	static boolean containsScopedEntry(Set<String> entries, String baseEntry, boolean instanced, boolean ignoreScope)
	{
		if (entries.contains(scoped(baseEntry, instanced)))
		{
			return true;
		}
		return ignoreScope && entries.contains(scoped(baseEntry, !instanced));
	}

	/**
	 * Resolves the map region (template space) an object belongs to. Safe on the
	 * maploader thread: only field reads on the scene/object. Returns -1 when
	 * the instance chunk has no template data.
	 */
	private static int templateRegionOf(TileObject object)
	{
		return templateRegionOf(sceneOf(object), object);
	}

	private static int templateRegionOf(Scene scene, TileObject object)
	{
		final WorldView wv = object.getWorldView();
		final WorldPoint wp = object.getWorldLocation();
		if (!scopeIsInstance(scene, object))
		{
			return wp.getRegionID();
		}
		if (scene == null && wv == null)
		{
			return -1;
		}

		final int[][][] chunks = scene == null ? wv.getInstanceTemplateChunks() : scene.getInstanceTemplateChunks();
		final int baseX = scene == null ? wv.getBaseX() : scene.getBaseX();
		final int baseY = scene == null ? wv.getBaseY() : scene.getBaseY();
		final int plane = wp.getPlane();
		if (chunks == null)
		{
			return -1;
		}
		final int chunkX = (wp.getX() - baseX) >> 3;
		final int chunkY = (wp.getY() - baseY) >> 3;
		if (plane < 0 || plane >= chunks.length
			|| chunkX < 0 || chunkX >= chunks[plane].length
			|| chunkY < 0 || chunkY >= chunks[plane][chunkX].length)
		{
			return -1;
		}
		final int packed = chunks[plane][chunkX][chunkY];
		if (packed == -1)
		{
			return -1;
		}
		// Same packing WorldPoint.fromLocalInstance decodes: template chunk
		// coords in tile units; rotation is irrelevant at region granularity
		final int templateTileY = (packed >> 3 & 2047) * 8;
		final int templateTileX = (packed >> 14 & 1023) * 8;
		return (templateTileX >> 6) << 8 | templateTileY >> 6;
	}

	private static Scene sceneOf(TileObject object)
	{
		final WorldView wv = object.getWorldView();
		return wv == null ? null : wv.getScene();
	}

	private static boolean scopeIsInstance(Scene scene, TileObject object)
	{
		if (scene != null)
		{
			return scene.isInstance();
		}
		final WorldView wv = object.getWorldView();
		return wv != null && wv.isInstance();
	}

	// --- lifecycle / sync ----------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			clearSceneScopedState();
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			maybeExpireActiveGroup();
			refresh();
		}
	}

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
		markPotentialScopeTransition(event.getWorldView());
		// Entering/leaving an instance reloads the world view mid-session
		refresh();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("activeGroup".equals(event.getKey()))
		{
			synchronized (this)
			{
				activeGroupName = config.activeGroup();
			}
			rebuildPanel();
			return;
		}
		if (!"groups".equals(event.getKey()) && !"revealAll".equals(event.getKey()))
		{
			return;
		}

		final Set<Integer> oldIds = new HashSet<>(activeHiddenIds);
		loadFromConfig();
		clientThread.invokeLater(() ->
		{
			oldIds.addAll(activeHiddenIds);
			refreshWithIds(oldIds);
		});
	}

	/**
	 * Recomputes the derived tile set, invalidates affected GPU zones, resolves
	 * object names, and rebuilds the panel. Must run on the client thread.
	 */
	private void refresh()
	{
		refreshWithIds(activeHiddenIds);
	}

	private void refreshWithIds(Set<Integer> idsToInvalidate)
	{
		// Union of old and new tile points / area pairs, captured around the
		// rebuild, so both newly hidden and newly revealed objects get their
		// zones re-uploaded. Both derived sets are (re)built in
		// rebuildActiveTilePoints, so they must be snapshotted here — not
		// passed in from onConfigChanged, where they are still stale.
		final Set<String> points = new HashSet<>(activeHiddenTilePoints);
		final Set<String> areas = new HashSet<>(activeAreaPairs);
		final WorldView topView = client.getTopLevelWorldView();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			// Mid-load: defer scene-scoped rebuild until the new scene is settled
			clearSceneScopedState();
			rebuildPanel();
			return;
		}

		rebuildActiveTilePoints();
		points.addAll(activeHiddenTilePoints);
		areas.addAll(activeAreaPairs);
		final boolean restoreUnscoped = consumeSceneScopeRestore(topView);
		invalidateZones(idsToInvalidate, points, areas, restoreUnscoped);
		resolveObjectNames();
		rebuildPanel();
	}

	private void clearSceneScopedState()
	{
		activeHiddenTilePoints.clear();
		activeAreaPairs.clear();
	}

	private void markPotentialScopeTransition(WorldView wv)
	{
		if (wv == null || wv.getId() != WorldView.TOPLEVEL)
		{
			return;
		}
		final Boolean last = lastTopLevelInstance;
		if (last != null && last != wv.isInstance())
		{
			pendingSceneScopeRestore = true;
		}
	}

	private boolean consumeSceneScopeRestore(WorldView wv)
	{
		boolean scopeChanged = false;
		if (wv != null)
		{
			final Boolean last = lastTopLevelInstance;
			scopeChanged = last != null && last != wv.isInstance();
			lastTopLevelInstance = wv.isInstance();
		}
		final boolean restore = pendingSceneScopeRestore || scopeChanged;
		pendingSceneScopeRestore = false;
		return restore;
	}

	/**
	 * Re-projects the persisted region-relative entries of all enabled groups
	 * into the current (possibly instanced) world view. The same template chunk
	 * can occur more than once in a raid layout, so one entry may yield several
	 * points.
	 */
	private void rebuildActiveTilePoints()
	{
		activeHiddenTilePoints.clear();
		activeAreaPairs.clear();

		final WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		final Set<String> tiles;
		final Set<String> areas;
		synchronized (this)
		{
			tiles = effectiveTiles(groups);
			areas = effectiveAreas(groups);
		}
		// Keys keep their scope tag; the scope is enforced per-object at draw time
		// (see matchesTile / matchesArea), not by filtering here.
		for (String entry : tiles)
		{
			final int[] parts = parseTileEntry(entry);
			if (parts == null)
			{
				continue;
			}
			final boolean instanced = isInstanceEntry(entry);
			final WorldPoint wp = WorldPoint.fromRegion(parts[1], parts[2], parts[3], parts[4]);
			for (WorldPoint occurrence : WorldPoint.toLocalInstance(wv, wp))
			{
				activeHiddenTilePoints.add(scoped(tileKey(parts[0], occurrence), instanced));
			}
		}
		for (String entry : areas)
		{
			final int[] parts = parseAreaEntry(entry);
			if (parts != null)
			{
				activeAreaPairs.add(scoped(areaKey(parts[0], parts[1]), isInstanceEntry(entry)));
			}
		}
	}

	// --- menu ----------------------------------------------------------------------

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (config.requireShift() && !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}

		final WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		final Scene scene = wv.getScene();

		// Attach our client-side options to object Examine entries. RUNELITE-typed
		// entries are consumed by the client and never sent to the server.
		final Set<Integer> coveredIds = new HashSet<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() != MenuAction.EXAMINE_OBJECT)
			{
				continue;
			}

			final int objectId = entry.getIdentifier();
			final TileObject obj = findTileObject(scene, wv.getPlane(), entry.getParam0(), entry.getParam1(), objectId);
			if (obj == null)
			{
				continue;
			}
			coveredIds.add(objectId);
			addMenuEntries(obj, entry.getTarget());
		}

		// Wall/decorative/ground objects often lack an Examine entry — offer
		// them via the hovered tile instead (same fallback as upstream).
		final Tile tile = wv.getSelectedSceneTile();
		if (tile == null)
		{
			return;
		}
		for (TileObject obj : tileObjects(tile))
		{
			final int objectId = obj.getId();
			if (obj instanceof GameObject || !coveredIds.add(objectId))
			{
				continue;
			}
			final String target = "<col=ffff>" + objectNames.computeIfAbsent(objectId, this::lookupName) + "</col>";
			addMenuEntries(obj, target);
		}
	}

	private void addMenuEntries(TileObject obj, String target)
	{
		final int objectId = obj.getId();

		final int regionId = templateRegionOf(obj);
		// Hides are scoped to where they're made: instance-made entries carry an
		// ":i" tag and never affect the template's real-world location
		final boolean instanced = scopeIsInstance(sceneOf(obj), obj);
		final String areaEntry = regionId == -1 ? null : scoped(areaKey(objectId, regionId), instanced);

		if (config.revealAll())
		{
			final String entry = scoped(regionKey(objectId, toTemplateWorldPoint(obj)), instanced);
			if (isTileEntryHidden(entry))
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(UNHIDE_ONE)
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> unhideTileEverywhere(entry));
			}
			if (areaEntry != null && activeAreaPairs.contains(areaEntry))
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(UNHIDE_AREA)
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> unhideAreaEverywhere(areaEntry));
			}
			if (activeHiddenIds.contains(objectId))
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(UNHIDE_ID)
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> unhideIdEverywhere(objectId));
			}
			return;
		}

		if (!isHidden(obj))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption(HIDE_ONE)
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> hideThisOne(obj));
		}
		if (areaEntry != null && !activeAreaPairs.contains(areaEntry))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption(HIDE_AREA)
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> hideAllOfIdInArea(objectId, regionId, instanced));
		}
		if (!activeHiddenIds.contains(objectId))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption(HIDE_ID)
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> hideAllOfId(objectId));
		}
	}

	private synchronized boolean isTileEntryHidden(String entry)
	{
		for (HideGroup group : groups)
		{
			if (group.isEnabled() && group.getTiles().contains(entry))
			{
				return true;
			}
		}
		return false;
	}

	// --- hide/unhide operations ------------------------------------------------------

	private void hideThisOne(TileObject obj)
	{
		if (rejectIfBanned(obj.getId()))
		{
			return;
		}
		maybeExpireActiveGroup();
		final String entry = scoped(regionKey(obj.getId(), toTemplateWorldPoint(obj)),
			scopeIsInstance(sceneOf(obj), obj));
		synchronized (this)
		{
			activeGroup().getTiles().add(entry);
		}
		objectNames.computeIfAbsent(obj.getId(), this::lookupName);
		touchActiveGroup();
		saveToConfig();
	}

	private void hideAllOfId(int objectId)
	{
		if (rejectIfBanned(objectId))
		{
			return;
		}
		maybeExpireActiveGroup();
		synchronized (this)
		{
			activeGroup().getIds().add(objectId);
		}
		objectNames.computeIfAbsent(objectId, this::lookupName);
		touchActiveGroup();
		saveToConfig();
	}

	private void hideAllOfIdInArea(int objectId, int regionId, boolean instanced)
	{
		if (rejectIfBanned(objectId))
		{
			return;
		}
		maybeExpireActiveGroup();
		synchronized (this)
		{
			activeGroup().getAreas().add(scoped(areaKey(objectId, regionId), instanced));
		}
		objectNames.computeIfAbsent(objectId, this::lookupName);
		touchActiveGroup();
		saveToConfig();
	}

	/** Reveal-mode unhide: removes the area pair from every group, so it actually reappears. */
	public void unhideAreaEverywhere(String entry)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				group.getAreas().remove(entry);
			}
		}
		saveToConfig();
	}

	/** Reveal-mode unhide: removes the entry from every group, so it actually reappears. */
	public void unhideTileEverywhere(String entry)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				group.getTiles().remove(entry);
			}
		}
		saveToConfig();
	}

	/** Reveal-mode unhide: removes the ID from every group, so it actually reappears. */
	public void unhideIdEverywhere(int objectId)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				group.getIds().remove(objectId);
			}
		}
		saveToConfig();
	}

	private boolean rejectIfBanned(int objectId)
	{
		if (!isBanned(objectId))
		{
			return false;
		}
		client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"",
			"Hiding this object is disallowed due to plugin hub rules.",
			null
		);
		return true;
	}

	static boolean isBanned(int objectId)
	{
		return BANNED_OBJECTS.contains(objectId);
	}

	// --- group management (called from the panel on the EDT and from menu clicks) -----

	/** The group new hides land in. Never null: invariants guarantee ≥1 group. */
	private HideGroup activeGroup()
	{
		for (HideGroup group : groups)
		{
			if (group.getName().equals(activeGroupName))
			{
				return group;
			}
		}
		return groups.get(0);
	}

	public void createGroup(String name)
	{
		synchronized (this)
		{
			final HideGroup group = new HideGroup();
			group.setName(uniqueName(sanitizeName(name), existingNames(groups)));
			groups.add(group);
			activeGroupName = group.getName();
		}
		saveToConfig();
	}

	/** Deletes a group. The Default group is cleared instead — it can never be removed. */
	public void deleteGroup(String name)
	{
		synchronized (this)
		{
			if (DEFAULT_GROUP_NAME.equals(name))
			{
				final HideGroup def = findGroup(groups, DEFAULT_GROUP_NAME);
				if (def != null)
				{
					def.getIds().clear();
					def.getTiles().clear();
					def.getAreas().clear();
				}
			}
			else
			{
				groups.removeIf(g -> g.getName().equals(name));
			}
			ensureInvariants();
		}
		saveToConfig();
	}

	/** Renames a group. The Default group keeps its name — it anchors the timeout fallback. */
	public void renameGroup(String oldName, String newName)
	{
		synchronized (this)
		{
			if (DEFAULT_GROUP_NAME.equals(oldName))
			{
				return;
			}
			final HideGroup group = findGroup(groups, oldName);
			if (group == null)
			{
				return;
			}
			final Set<String> taken = existingNames(groups);
			taken.remove(oldName);
			final String name = uniqueName(sanitizeName(newName), taken);
			if (name.equals(oldName))
			{
				return;
			}
			group.setName(name);
			if (activeGroupName.equals(oldName))
			{
				activeGroupName = name;
			}
		}
		saveToConfig();
	}

	public void setGroupEnabled(String name, boolean enabled)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				if (group.getName().equals(name))
				{
					group.setEnabled(enabled);
				}
			}
		}
		saveToConfig();
	}

	public void setActiveGroup(String name)
	{
		synchronized (this)
		{
			activeGroupName = name;
		}
		touchActiveGroup();
		configManager.setConfiguration(CONFIG_GROUP, "activeGroup", name);
	}

	public void removeIdFromGroup(String groupName, int objectId)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				if (group.getName().equals(groupName))
				{
					group.getIds().remove(objectId);
				}
			}
		}
		saveToConfig();
	}

	public void removeTileFromGroup(String groupName, String entry)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				if (group.getName().equals(groupName))
				{
					group.getTiles().remove(entry);
				}
			}
		}
		saveToConfig();
	}

	public void removeAreaFromGroup(String groupName, String entry)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				if (group.getName().equals(groupName))
				{
					group.getAreas().remove(entry);
				}
			}
		}
		saveToConfig();
	}

	/** Drag-and-drop: moves an area-hide from one group to another. */
	public void moveAreaToGroup(String fromGroup, String toGroup, String entry)
	{
		final boolean moved;
		synchronized (this)
		{
			moved = moveArea(groups, fromGroup, toGroup, entry);
		}
		if (moved)
		{
			saveToConfig();
		}
	}

	/** Removes every tile-hide of the given object ID from one group (the ×N remove-all). */
	public void removeTilesOfIdFromGroup(String groupName, int objectId)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				if (group.getName().equals(groupName))
				{
					final Iterator<String> it = group.getTiles().iterator();
					while (it.hasNext())
					{
						final int[] parts = parseTileEntry(it.next());
						if (parts != null && parts[0] == objectId)
						{
							it.remove();
						}
					}
				}
			}
		}
		saveToConfig();
	}

	/** Drag-and-drop: moves an ID-hide from one group to another. */
	public void moveIdToGroup(String fromGroup, String toGroup, int objectId)
	{
		final boolean moved;
		synchronized (this)
		{
			moved = moveId(groups, fromGroup, toGroup, objectId);
		}
		if (moved)
		{
			saveToConfig();
		}
	}

	/** Drag-and-drop: moves a single tile-hide from one group to another. */
	public void moveTileToGroup(String fromGroup, String toGroup, String entry)
	{
		final boolean moved;
		synchronized (this)
		{
			moved = moveTile(groups, fromGroup, toGroup, entry);
		}
		if (moved)
		{
			saveToConfig();
		}
	}

	/** Drag-and-drop: moves every tile-hide of an object ID from one group to another. */
	public void moveTilesOfIdToGroup(String fromGroup, String toGroup, int objectId)
	{
		final boolean moved;
		synchronized (this)
		{
			moved = moveTilesOfId(groups, fromGroup, toGroup, objectId);
		}
		if (moved)
		{
			saveToConfig();
		}
	}

	// --- active-group idle timeout -------------------------------------------------------

	private static final String LAST_USE_KEY = "activeGroupLastUse";

	/**
	 * If the active group hasn't been used within the configured timeout, new
	 * hides revert to the Default group — a selection forgotten for days must
	 * not silently collect stray hides. The timer refreshes on every hide and
	 * every explicit active-group pick, so it never resets mid-project. Runs
	 * on the client thread (login and hide-time), never mid-design.
	 */
	private void maybeExpireActiveGroup()
	{
		final String raw = configManager.getConfiguration(CONFIG_GROUP, LAST_USE_KEY);
		if (raw == null)
		{
			// First run: start the clock, never punish retroactively
			touchActiveGroup();
			return;
		}
		if (!isActiveGroupExpired(raw, System.currentTimeMillis(), config.activeGroupTimeoutMinutes()))
		{
			return;
		}

		final String target;
		synchronized (this)
		{
			target = expiryTarget(groups);
			if (activeGroupName.equals(target))
			{
				touchActiveGroup();
				return;
			}
			activeGroupName = target;
		}
		touchActiveGroup();
		configManager.setConfiguration(CONFIG_GROUP, "activeGroup", target);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Better Object Hider: active group reset to \"" + target + "\" after inactivity.", null);
		}
	}

	private void touchActiveGroup()
	{
		configManager.setConfiguration(CONFIG_GROUP, LAST_USE_KEY, String.valueOf(System.currentTimeMillis()));
	}

	static boolean isActiveGroupExpired(String lastUseRaw, long now, int timeoutMinutes)
	{
		if (timeoutMinutes <= 0 || lastUseRaw == null)
		{
			return false;
		}
		try
		{
			return now - Long.parseLong(lastUseRaw) > timeoutMinutes * 60_000L;
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	/** Where an expired selection falls back to: Default if it exists, else the first group. */
	static String expiryTarget(List<HideGroup> groups)
	{
		for (HideGroup group : groups)
		{
			if (DEFAULT_GROUP_NAME.equals(group.getName()))
			{
				return DEFAULT_GROUP_NAME;
			}
		}
		return groups.isEmpty() ? DEFAULT_GROUP_NAME : groups.get(0).getName();
	}

	/** Must be called inside synchronized(this). */
	private void ensureInvariants()
	{
		ensureDefaultGroup(groups);
		boolean activeExists = false;
		for (HideGroup group : groups)
		{
			if (group.getName().equals(activeGroupName))
			{
				activeExists = true;
				break;
			}
		}
		if (!activeExists)
		{
			activeGroupName = groups.get(0).getName();
		}
	}

	// --- import / export ---------------------------------------------------------------

	/** Result of an import attempt, so the panel can style success vs. error dialogs. */
	public static final class ImportResult
	{
		public final boolean success;
		public final String message;

		ImportResult(boolean success, String message)
		{
			this.success = success;
			this.message = message;
		}
	}

	public boolean isRevealAll()
	{
		return config.revealAll();
	}

	/**
	 * Copies the group's JSON to the system clipboard. Feedback goes to the
	 * game chat when logged in (routine action, no modal needed — same as
	 * Ground Markers); otherwise the message is returned for a dialog.
	 *
	 * @return a message for the panel to show, or null if chat handled it
	 */
	public String exportGroupToClipboard(String groupName)
	{
		final String json;
		final int count;
		synchronized (this)
		{
			HideGroup found = null;
			for (HideGroup group : groups)
			{
				if (group.getName().equals(groupName))
				{
					found = group;
					break;
				}
			}
			if (found == null)
			{
				return "Group not found.";
			}
			count = found.getIds().size() + found.getTiles().size() + found.getAreas().size();
			json = gson.toJson(found);
		}
		Toolkit.getDefaultToolkit()
			.getSystemClipboard()
			.setContents(new StringSelection(json), null);

		final String message = "Copied \"" + groupName + "\" (" + count + " entries) to the clipboard.";
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Better Object Hider: " + message, null));
			return null;
		}
		return message;
	}

	/**
	 * Imports a group from JSON on the system clipboard. Sanitizes defensively:
	 * imported data is untrusted (nulls, garbage entries, banned IDs).
	 */
	public ImportResult importGroupFromClipboard()
	{
		final String text;
		try
		{
			text = Toolkit.getDefaultToolkit()
				.getSystemClipboard()
				.getData(DataFlavor.stringFlavor)
				.toString();
		}
		catch (IOException | UnsupportedFlavorException ex)
		{
			return new ImportResult(false, "Unable to read the system clipboard.");
		}

		HideGroup imported;
		try
		{
			imported = gson.fromJson(text, HideGroup.class);
		}
		catch (JsonSyntaxException ex)
		{
			log.debug("malformed import", ex);
			return new ImportResult(false, "The clipboard does not contain a valid hide group.");
		}

		imported = sanitizeGroup(imported);
		if (imported == null
			|| (imported.getIds().isEmpty() && imported.getTiles().isEmpty() && imported.getAreas().isEmpty()))
		{
			return new ImportResult(false, "The clipboard does not contain a valid hide group.");
		}

		final String name;
		final int count = imported.getIds().size() + imported.getTiles().size() + imported.getAreas().size();
		synchronized (this)
		{
			name = uniqueName(imported.getName(), existingNames(groups));
			imported.setName(name);
			groups.add(imported);
		}
		saveToConfig();
		return new ImportResult(true, "Imported \"" + name + "\" (" + count + " entries).");
	}

	// --- pure helpers (static, unit-tested) ----------------------------------------------

	/** Union of hidden IDs over enabled groups. */
	static Set<Integer> effectiveIds(Collection<HideGroup> groups)
	{
		final Set<Integer> out = new HashSet<>();
		for (HideGroup group : groups)
		{
			if (group.isEnabled())
			{
				out.addAll(group.getIds());
			}
		}
		return out;
	}

	/** Union of tile entries over enabled groups. */
	static Set<String> effectiveTiles(Collection<HideGroup> groups)
	{
		final Set<String> out = new HashSet<>();
		for (HideGroup group : groups)
		{
			if (group.isEnabled())
			{
				out.addAll(group.getTiles());
			}
		}
		return out;
	}

	/** Union of area entries over enabled groups. */
	static Set<String> effectiveAreas(Collection<HideGroup> groups)
	{
		final Set<String> out = new HashSet<>();
		for (HideGroup group : groups)
		{
			if (group.isEnabled())
			{
				out.addAll(group.getAreas());
			}
		}
		return out;
	}

	/**
	 * Cleans an untrusted group (import or config): null-safe sets, valid entry
	 * formats only, banned IDs dropped, sane name.
	 *
	 * @return the cleaned group, or null if the input was null
	 */
	static HideGroup sanitizeGroup(HideGroup group)
	{
		if (group == null)
		{
			return null;
		}
		final HideGroup out = new HideGroup();
		out.setName(sanitizeName(group.getName()));
		out.setEnabled(group.isEnabled());
		if (group.getIds() != null)
		{
			for (Integer id : group.getIds())
			{
				if (id != null && id > 0 && !isBanned(id))
				{
					out.getIds().add(id);
				}
			}
		}
		if (group.getTiles() != null)
		{
			for (String tile : group.getTiles())
			{
				if (tile == null)
				{
					continue;
				}
				final int[] parts = parseTileEntry(tile);
				if (parts != null && !isBanned(parts[0]))
				{
					out.getTiles().add(tile);
				}
			}
		}
		if (group.getAreas() != null)
		{
			for (String area : group.getAreas())
			{
				if (area == null)
				{
					continue;
				}
				final int[] parts = parseAreaEntry(area);
				if (parts != null && !isBanned(parts[0]))
				{
					out.getAreas().add(area);
				}
			}
		}
		return out;
	}

	static String sanitizeName(String name)
	{
		if (name == null)
		{
			return DEFAULT_GROUP_NAME;
		}
		// Strip angle brackets: a name is rendered at the start of a Swing JLabel,
		// which interprets leading "<html>" as markup and would fetch remote
		// <img> URLs from an imported (untrusted) group name.
		final String trimmed = name.replaceAll("[<>]", "").trim();
		if (trimmed.isEmpty())
		{
			return DEFAULT_GROUP_NAME;
		}
		return trimmed.length() <= MAX_GROUP_NAME_LENGTH
			? trimmed
			: trimmed.substring(0, MAX_GROUP_NAME_LENGTH);
	}

	/** Appends " (2)", " (3)", ... until the name is free. */
	static String uniqueName(String base, Set<String> taken)
	{
		if (!taken.contains(base))
		{
			return base;
		}
		for (int i = 2; ; i++)
		{
			final String candidate = base + " (" + i + ")";
			if (!taken.contains(candidate))
			{
				return candidate;
			}
		}
	}

	static Set<String> existingNames(Collection<HideGroup> groups)
	{
		final Set<String> names = new HashSet<>();
		for (HideGroup group : groups)
		{
			names.add(group.getName());
		}
		return names;
	}

	/**
	 * The Default group always exists — the safeguard runs on every config load,
	 * so a profile that lost it (older builds allowed deleting it, or a hand-edit)
	 * self-heals at the next sync/login.
	 */
	static void ensureDefaultGroup(List<HideGroup> groups)
	{
		if (findGroup(groups, DEFAULT_GROUP_NAME) == null)
		{
			final HideGroup def = new HideGroup();
			def.setName(DEFAULT_GROUP_NAME);
			groups.add(0, def);
		}
	}

	static HideGroup findGroup(Collection<HideGroup> groups, String name)
	{
		for (HideGroup group : groups)
		{
			if (group.getName().equals(name))
			{
				return group;
			}
		}
		return null;
	}

	/** @return true if the ID existed in the source group and was moved */
	static boolean moveId(Collection<HideGroup> groups, String fromGroup, String toGroup, int objectId)
	{
		final HideGroup from = findGroup(groups, fromGroup);
		final HideGroup to = findGroup(groups, toGroup);
		if (from == null || to == null || from == to || !from.getIds().remove(objectId))
		{
			return false;
		}
		to.getIds().add(objectId);
		return true;
	}

	/** @return true if the tile entry existed in the source group and was moved */
	static boolean moveTile(Collection<HideGroup> groups, String fromGroup, String toGroup, String entry)
	{
		final HideGroup from = findGroup(groups, fromGroup);
		final HideGroup to = findGroup(groups, toGroup);
		if (from == null || to == null || from == to || !from.getTiles().remove(entry))
		{
			return false;
		}
		to.getTiles().add(entry);
		return true;
	}

	/** @return true if the area entry existed in the source group and was moved */
	static boolean moveArea(Collection<HideGroup> groups, String fromGroup, String toGroup, String entry)
	{
		final HideGroup from = findGroup(groups, fromGroup);
		final HideGroup to = findGroup(groups, toGroup);
		if (from == null || to == null || from == to || !from.getAreas().remove(entry))
		{
			return false;
		}
		to.getAreas().add(entry);
		return true;
	}

	/** @return true if any tile-hide of the object ID was moved */
	static boolean moveTilesOfId(Collection<HideGroup> groups, String fromGroup, String toGroup, int objectId)
	{
		final HideGroup from = findGroup(groups, fromGroup);
		final HideGroup to = findGroup(groups, toGroup);
		if (from == null || to == null || from == to)
		{
			return false;
		}
		boolean moved = false;
		final Iterator<String> it = from.getTiles().iterator();
		while (it.hasNext())
		{
			final String entry = it.next();
			final int[] parts = parseTileEntry(entry);
			if (parts != null && parts[0] == objectId)
			{
				it.remove();
				to.getTiles().add(entry);
				moved = true;
			}
		}
		return moved;
	}

	/** Folds the pre-groups flat CSV config values into a single group. */
	static HideGroup migrateLegacy(String idsCsv, String tilesCsv)
	{
		final HideGroup group = new HideGroup();
		group.setName(DEFAULT_GROUP_NAME);
		if (idsCsv != null && !idsCsv.isEmpty())
		{
			Arrays.stream(idsCsv.split(","))
				.map(String::trim)
				.filter(s -> s.matches("\\d+"))
				.map(Integer::parseInt)
				.filter(id -> !isBanned(id))
				.forEach(group.getIds()::add);
		}
		if (tilesCsv != null && !tilesCsv.isEmpty())
		{
			Arrays.stream(tilesCsv.split("[,\\n]"))
				.map(String::trim)
				.filter(s ->
				{
					final int[] parts = parseTileEntry(s);
					return parts != null && !isBanned(parts[0]);
				})
				.forEach(group.getTiles()::add);
		}
		return group;
	}

	/**
	 * Parses the persisted groups JSON. A bad config value must never break
	 * startUp(), so malformed input falls back to a single empty Default group.
	 */
	static List<HideGroup> parseGroups(Gson gson, String json)
	{
		List<HideGroup> parsed = null;
		if (json != null && !json.isEmpty())
		{
			try
			{
				parsed = gson.fromJson(json, new TypeToken<List<HideGroup>>()
				{
				}.getType());
			}
			catch (JsonSyntaxException ex)
			{
				log.warn("corrupt groups config, resetting", ex);
			}
		}

		final List<HideGroup> out = new ArrayList<>();
		if (parsed != null)
		{
			final Set<String> names = new HashSet<>();
			for (HideGroup group : parsed)
			{
				final HideGroup clean = sanitizeGroup(group);
				if (clean != null)
				{
					clean.setName(uniqueName(clean.getName(), names));
					names.add(clean.getName());
					out.add(clean);
				}
			}
		}
		if (out.isEmpty())
		{
			final HideGroup def = new HideGroup();
			def.setName(DEFAULT_GROUP_NAME);
			out.add(def);
		}
		return out;
	}

	// --- coordinate keys -----------------------------------------------------------

	/**
	 * De-instances the tile that {@link TileObject#getWorldLocation()} refers to.
	 * Keyed off getWorldLocation (not getLocalLocation) so hide-time and draw-time
	 * agree on the same tile for multi-tile objects, whose local center can round
	 * to a different tile than the "center rounded south-west" world location.
	 */
	private WorldPoint toTemplateWorldPoint(TileObject obj)
	{
		final WorldView wv = obj.getWorldView();
		final WorldPoint wp = obj.getWorldLocation();
		final LocalPoint lp = LocalPoint.fromScene(wp.getX() - wv.getBaseX(), wp.getY() - wv.getBaseY(), wv);
		return WorldPoint.fromLocalInstance(client, lp, wp.getPlane());
	}

	// Scene-space lookup key used by drawObject()
	static String tileKey(int objectId, WorldPoint wp)
	{
		return objectId + ":" + wp.getX() + ":" + wp.getY() + ":" + wp.getPlane();
	}

	// Persisted, instance-stable key
	static String regionKey(int objectId, WorldPoint templatePoint)
	{
		return objectId + ":" + templatePoint.getRegionID()
			+ ":" + templatePoint.getRegionX()
			+ ":" + templatePoint.getRegionY()
			+ ":" + templatePoint.getPlane();
	}

	// Region-scoped hide key, template space
	static String areaKey(int objectId, int regionId)
	{
		return objectId + ":" + regionId;
	}

	/**
	 * Entries created inside an instance carry an ":i" suffix and apply only in
	 * instances; unsuffixed entries apply only in the overworld. This keeps a
	 * hide made in e.g. the Fight Caves from also hiding the template's
	 * real-world map area right outside it.
	 */
	public static boolean isInstanceEntry(String entry)
	{
		return entry.endsWith(":i");
	}

	static String scoped(String entry, boolean instanced)
	{
		return instanced ? entry + ":i" : entry;
	}

	/**
	 * Parses a persisted "id:regionId[:i]" area entry.
	 *
	 * @return {id, regionId}, or null if malformed
	 */
	public static int[] parseAreaEntry(String entry)
	{
		final String[] parts = entry.split(":");
		if (parts.length != 2 && !(parts.length == 3 && "i".equals(parts[2])))
		{
			return null;
		}
		try
		{
			final int id = Integer.parseInt(parts[0]);
			final int regionId = Integer.parseInt(parts[1]);
			return (id <= 0 || regionId < 0) ? null : new int[]{id, regionId};
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Parses a persisted "id:regionId:regionX:regionY:plane[:i]" entry.
	 *
	 * @return {id, regionId, regionX, regionY, plane}, or null if malformed
	 */
	public static int[] parseTileEntry(String entry)
	{
		final String[] parts = entry.split(":");
		if (parts.length != 5 && !(parts.length == 6 && "i".equals(parts[5])))
		{
			return null;
		}
		final int[] out = new int[5];
		for (int i = 0; i < 5; i++)
		{
			try
			{
				out[i] = Integer.parseInt(parts[i]);
			}
			catch (NumberFormatException e)
			{
				return null;
			}
			if (out[i] < 0)
			{
				return null;
			}
		}
		return out;
	}

	// --- tile object lookup -----------------------------------------------------------

	private static TileObject findTileObject(Scene scene, int plane, int sceneX, int sceneY, int objectId)
	{
		final Tile[][][] tiles = scene.getTiles();
		if (plane < 0 || plane >= tiles.length
			|| sceneX < 0 || sceneX >= tiles[plane].length
			|| sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
		{
			return null;
		}
		final Tile tile = tiles[plane][sceneX][sceneY];
		if (tile == null)
		{
			return null;
		}
		for (TileObject obj : tileObjects(tile))
		{
			if (obj.getId() == objectId)
			{
				return obj;
			}
		}
		return null;
	}

	private static List<TileObject> tileObjects(Tile tile)
	{
		final List<TileObject> out = new ArrayList<>();
		final GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject go : gameObjects)
			{
				if (go != null)
				{
					out.add(go);
				}
			}
		}
		if (tile.getWallObject() != null)
		{
			out.add(tile.getWallObject());
		}
		if (tile.getDecorativeObject() != null)
		{
			out.add(tile.getDecorativeObject());
		}
		if (tile.getGroundObject() != null)
		{
			out.add(tile.getGroundObject());
		}
		return out;
	}

	// --- GPU zone invalidation ---------------------------------------------------------

	/**
	 * Scans the scene for tile objects matching any of the given hides and
	 * invalidates their zones so they re-upload with the current suppression
	 * state. Only zones where a matching object is found are touched — those
	 * are guaranteed initialized, avoiding the {@code assert zone.initialized}
	 * crash. Must run on the client thread.
	 */
	private void invalidateZones(Set<Integer> ids, Set<String> tilePoints, Set<String> areaPairs)
	{
		invalidateZones(ids, tilePoints, areaPairs, false);
	}

	private void invalidateZones(Set<Integer> ids, Set<String> tilePoints, Set<String> areaPairs, boolean ignoreScope)
	{
		if ((ids.isEmpty() && tilePoints.isEmpty() && areaPairs.isEmpty())
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final WorldView wv = client.getTopLevelWorldView();
		final DrawCallbacks dc = client.getDrawCallbacks();
		if (wv == null || dc == null)
		{
			return;
		}

		final Scene scene = wv.getScene();
		final Set<Long> done = new HashSet<>();
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] column : plane)
			{
				if (column == null)
				{
					continue;
				}
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					for (TileObject obj : tileObjects(tile))
					{
						if (ids.contains(obj.getId())
							|| matchesTile(tilePoints, scene, obj, ignoreScope)
							|| matchesArea(areaPairs, scene, obj, ignoreScope))
						{
							invalidateZoneForObject(scene, dc, obj, done);
							break;
						}
					}
				}
			}
		}
	}

	private static void invalidateZoneForObject(Scene scene, DrawCallbacks dc, TileObject obj, Set<Long> done)
	{
		// Scene tile coordinates from the TileObject hash (bits 0-6 = sceneX, bits 7-13 = sceneY)
		final long hash = obj.getHash();
		final int sceneX = (int) (hash & 127);
		final int sceneY = (int) ((hash >> 7) & 127);
		// Zone is 8 tiles; add ZONE_OFFSET for extended scene padding
		final int zx = (sceneX >> 3) + ZONE_OFFSET;
		final int zz = (sceneY >> 3) + ZONE_OFFSET;
		final long key = ((long) zx << 32) | zz;
		if (done.add(key))
		{
			dc.invalidateZone(scene, zx, zz);
		}
	}

	// --- object names --------------------------------------------------------------------

	/** Resolves display names for all hidden entries. Must run on the client thread. */
	private void resolveObjectNames()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final Set<Integer> wanted = new HashSet<>();
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				wanted.addAll(group.getIds());
				for (String entry : group.getTiles())
				{
					final int[] parts = parseTileEntry(entry);
					if (parts != null)
					{
						wanted.add(parts[0]);
					}
				}
				for (String entry : group.getAreas())
				{
					final int[] parts = parseAreaEntry(entry);
					if (parts != null)
					{
						wanted.add(parts[0]);
					}
				}
			}
		}
		for (int id : wanted)
		{
			objectNames.computeIfAbsent(id, this::lookupName);
		}
	}

	private String lookupName(int objectId)
	{
		ObjectComposition comp = client.getObjectDefinition(objectId);
		if (comp != null && comp.getImpostorIds() != null)
		{
			final ObjectComposition impostor = comp.getImpostor();
			if (impostor != null)
			{
				comp = impostor;
			}
		}
		return displayName(comp == null ? null : comp.getName(), objectId);
	}

	static String displayName(String rawName, int objectId)
	{
		if (rawName == null || rawName.isEmpty() || "null".equals(rawName))
		{
			return "Object #" + objectId;
		}
		return rawName;
	}

	public String getObjectName(int objectId)
	{
		return objectNames.getOrDefault(objectId, "Object #" + objectId);
	}

	// --- panel accessors --------------------------------------------------------------------

	/** Deep-copied snapshot for the EDT: safe to iterate while groups mutate. */
	public synchronized List<HideGroup> getGroupsSnapshot()
	{
		final List<HideGroup> out = new ArrayList<>(groups.size());
		for (HideGroup group : groups)
		{
			final HideGroup copy = new HideGroup();
			copy.setName(group.getName());
			copy.setEnabled(group.isEnabled());
			copy.getIds().addAll(group.getIds());
			copy.getTiles().addAll(group.getTiles());
			copy.getAreas().addAll(group.getAreas());
			out.add(copy);
		}
		return out;
	}

	public synchronized String getActiveGroupName()
	{
		return activeGroupName;
	}

	private void rebuildPanel()
	{
		final BetterObjectHiderPanel panel = pluginPanel;
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::rebuild);
		}
	}

	// --- config (de)serialization -------------------------------------------------

	/** One-time: folds pre-groups flat hiddenIds/hiddenTiles config into a Default group. */
	private void migrateLegacyConfig()
	{
		final String groupsJson = configManager.getConfiguration(CONFIG_GROUP, "groups");
		if (groupsJson != null && !groupsJson.isEmpty())
		{
			return;
		}
		final String legacyIds = configManager.getConfiguration(CONFIG_GROUP, "hiddenIds");
		final String legacyTiles = configManager.getConfiguration(CONFIG_GROUP, "hiddenTiles");
		if ((legacyIds == null || legacyIds.isEmpty()) && (legacyTiles == null || legacyTiles.isEmpty()))
		{
			return;
		}
		final HideGroup migrated = migrateLegacy(legacyIds, legacyTiles);
		configManager.setConfiguration(CONFIG_GROUP, "groups", gson.toJson(List.of(migrated)));
		configManager.setConfiguration(CONFIG_GROUP, "activeGroup", migrated.getName());
		configManager.unsetConfiguration(CONFIG_GROUP, "hiddenIds");
		configManager.unsetConfiguration(CONFIG_GROUP, "hiddenTiles");
		log.info("migrated legacy hide lists into group \"{}\"", migrated.getName());
	}

	private void loadFromConfig()
	{
		final List<HideGroup> parsed = parseGroups(gson, config.groups());
		final Set<Integer> ids;
		synchronized (this)
		{
			groups.clear();
			groups.addAll(parsed);
			activeGroupName = config.activeGroup();
			ensureInvariants();
			ids = effectiveIds(groups);
		}
		activeHiddenIds.clear();
		activeHiddenIds.addAll(ids);
		// activeAreaPairs is view-scoped (instance vs overworld) and is rebuilt
		// on the client thread in rebuildActiveTilePoints()
	}

	private void saveToConfig()
	{
		final String json;
		final String active;
		synchronized (this)
		{
			json = gson.toJson(groups);
			active = activeGroupName;
		}
		// Writing config fires onConfigChanged, which re-syncs, invalidates
		// affected zones, and rebuilds the panel.
		configManager.setConfiguration(CONFIG_GROUP, "groups", json);
		configManager.setConfiguration(CONFIG_GROUP, "activeGroup", active);
	}
}
