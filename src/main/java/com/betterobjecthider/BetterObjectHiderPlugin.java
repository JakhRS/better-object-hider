/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 *
 * Inspired by LuxOG's custom-object-hider (BSD-2-Clause).
 * This plugin is purely cosmetic: it changes only what is rendered
 * client-side. It sends nothing to the server and automates nothing.
 *
 * Objects are hidden by their in-game NAME plus a map location — never by
 * object ID. You point at a named object you can see and hide that object at
 * that spot; objects with no name cannot be hidden. No IDs are entered,
 * stored, or targeted.
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.WallObjectSpawned;
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
	description = "Hide specific named scenery objects by name and location, organized into shareable groups",
	tags = {"hide", "object", "scenery", "declutter", "qol"}
)
public class BetterObjectHiderPlugin extends Plugin implements RenderCallback
{
	static final String CONFIG_GROUP = "betterobjecthider";
	public static final String DEFAULT_GROUP_NAME = "Default";
	static final int MAX_GROUP_NAME_LENGTH = 40;

	private static final String GROUPS_KEY = "groupsV2";
	private static final String ACTIVE_KEY = "activeGroup";
	private static final String LAST_USE_KEY = "activeGroupLastUse";

	// Bounds on clipboard imports (untrusted input). Loading an existing config
	// is intentionally uncapped — a profile that already holds more must keep working.
	static final int MAX_IMPORT_CHARS = 262_144;
	static final int MAX_IMPORT_ENTRIES = 2_000;

	// Colour-coded by reach (a traffic-light ladder): green = this one tile,
	// amber = this map area, red = everywhere. Every option is by object name.
	private static final String HIDE_ONE = "<col=00ff7f>Hide this</col>";
	private static final String HIDE_AREA = "<col=ffd166>Hide all in this area</col>";
	private static final String HIDE_ALL = "<col=ff6b6b>Hide all everywhere</col>";
	private static final String UNHIDE_ONE = "<col=00ff7f>Unhide this</col>";
	private static final String UNHIDE_AREA = "<col=ffd166>Unhide all in this area</col>";
	private static final String UNHIDE_ALL = "<col=ff6b6b>Unhide all everywhere</col>";

	// Objects whose names we refuse to hide even when named — raid mechanic
	// objects (RULES.md: "Sotetseg maze reveal" is prohibited). Resolved to
	// names on startup; unnamed mechanic objects are already blocked by the
	// named-only rule, making this belt-and-suspenders.
	private static final Set<Integer> BANNED_OBJECT_IDS = Set.of(
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

	// Persisted model. Mutated only inside synchronized(this) — mutations arrive
	// from both the client thread (menu clicks) and the EDT (panel buttons).
	private final List<HideGroup> groups = new ArrayList<>();
	private String activeGroupName = DEFAULT_GROUP_NAME;

	// Derived render state: packed id+position keys (see tileKey) of objects that
	// should currently be suppressed. Names are resolved on the client thread
	// (see rebuildHiddenPositions); drawObject on the maploader thread only does
	// a cheap position lookup. Concurrent because drawObject reads it off-thread.
	private final Set<Long> hiddenPositions = ConcurrentHashMap.newKeySet();

	// Snapshot of the enabled-group hides + their names, recomputed on every
	// config change. Read (without locking) by the scene scan and by the object
	// spawn handlers, so newly-spawned objects can be matched without touching
	// the groups list.
	private volatile Set<HideEntry> effectiveEntries = Set.of();
	private volatile Set<String> effectiveNames = Set.of();

	// Names of banned mechanic objects, resolved once on the client thread.
	private final Set<String> bannedNames = ConcurrentHashMap.newKeySet();

	// The most recent in-game hide this session, so the panel can offer one-click
	// undo (an accidental "Hide all in this area" shouldn't need panel archaeology).
	// The group is recorded too: undo must revert exactly that addition, never an
	// equal entry in another group. Guarded by synchronized(this); session-only.
	private HideEntry lastHide;
	private String lastHideGroup;

	private BetterObjectHiderPanel pluginPanel;
	private NavigationButton navigationButton;

	@Provides
	BetterObjectHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterObjectHiderConfig.class);
	}

	@Override
	protected void startUp()
	{
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

		clientThread.invokeLater(() ->
		{
			resolveBannedNames();
			refresh();
		});
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(this);
		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		pluginPanel = null;

		final Set<Long> restore = new HashSet<>(hiddenPositions);
		synchronized (this)
		{
			groups.clear();
		}
		hiddenPositions.clear();
		bannedNames.clear();
		// Re-upload the zones we were suppressing so the objects come back
		clientThread.invokeLater(() -> invalidateZones(restore));
	}

	// --- RenderCallback (maploader thread) -------------------------------------------

	@Override
	public boolean drawObject(Scene scene, TileObject object)
	{
		if (hiddenPositions.isEmpty()
			|| !hiddenPositions.contains(tileKey(object.getId(), object.getWorldLocation())))
		{
			return true;
		}
		// In reveal mode hidden objects stay visible so they can be unhidden
		return config.revealAll();
	}

	// --- lifecycle / sync (client thread) --------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			// Don't let a previous scene's positions suppress the incoming scene
			hiddenPositions.clear();
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
		// Fires when entering/leaving an instance or changing regions
		refresh();
	}

	// Objects that spawn AFTER the scene has settled (e.g. objects a boss spawns
	// mid-fight) never go through the full scan, so match them individually here.
	// All spawn events are on the client thread, where name resolution is safe.

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		handleSpawn(event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		handleSpawn(event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		handleSpawn(event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		handleSpawn(event.getGroundObject());
	}

	private void handleSpawn(TileObject obj)
	{
		if (obj == null || effectiveNames.isEmpty())
		{
			return;
		}
		final String name = resolveName(obj.getId());
		if (name == null || !effectiveNames.contains(name))
		{
			return;
		}
		if (matchesAny(effectiveEntries, obj, name)
			&& hiddenPositions.add(tileKey(obj.getId(), obj.getWorldLocation())))
		{
			// Force this object's zone to re-upload suppressed (static spawns);
			// animated objects re-evaluate drawObject next frame regardless.
			invalidateObjectZone(obj);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (ACTIVE_KEY.equals(event.getKey()) || "showHelp".equals(event.getKey()))
		{
			synchronized (this)
			{
				activeGroupName = config.activeGroup();
			}
			rebuildPanel();
			return;
		}
		if (!GROUPS_KEY.equals(event.getKey()) && !"revealAll".equals(event.getKey()))
		{
			return;
		}
		loadFromConfig();
		clientThread.invokeLater(this::refresh);
	}

	/**
	 * Recomputes which on-screen objects should be hidden (by resolving names on
	 * the client thread), invalidates the affected GPU zones, and rebuilds the
	 * panel. Must run on the client thread.
	 */
	private void refresh()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			hiddenPositions.clear();
			rebuildPanel();
			return;
		}
		final Set<Long> union = new HashSet<>(hiddenPositions);
		rebuildHiddenPositions();
		union.addAll(hiddenPositions);
		invalidateZones(union);
		rebuildPanel();
	}

	/**
	 * Walks the loaded scene, resolves each object's name, and records the
	 * positions of objects that match an enabled hide. This is where the
	 * name → object resolution happens — on the client thread, never in
	 * {@link #drawObject}. Cost is bounded to scene loads / hide changes, and
	 * short-circuits when nothing is hidden.
	 */
	private void rebuildHiddenPositions()
	{
		hiddenPositions.clear();

		final WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		final Set<HideEntry> effective = effectiveEntries;
		final Set<String> names = effectiveNames;
		if (effective.isEmpty())
		{
			return;
		}

		// Scenes repeat object ids heavily (every "Tree" is the same few ids), so
		// resolve each id once per walk. Unnamed ids cache null.
		final Map<Integer, String> nameCache = new HashMap<>();

		final Scene scene = wv.getScene();
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
						final int id = obj.getId();
						final String name;
						if (nameCache.containsKey(id))
						{
							name = nameCache.get(id);
						}
						else
						{
							name = resolveName(id);
							nameCache.put(id, name);
						}
						if (name == null || !names.contains(name))
						{
							continue;
						}
						if (matchesAny(effective, obj, name))
						{
							hiddenPositions.add(tileKey(id, obj.getWorldLocation()));
						}
					}
				}
			}
		}
	}

	// --- menu (client thread) --------------------------------------------------------

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

		// Dedupe on id+tile (not bare id) so two same-id objects under the cursor
		// each get their own correctly-targeted options; dedupe the HideEntry
		// options themselves so same-name neighbours don't repeat area/global rows.
		final Set<Long> covered = new HashSet<>();
		final Set<HideEntry> offered = new HashSet<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() != MenuAction.EXAMINE_OBJECT)
			{
				continue;
			}
			final TileObject obj = findTileObject(scene, wv.getPlane(), entry.getParam0(), entry.getParam1(), entry.getIdentifier());
			if (obj != null && covered.add(tileKey(obj.getId(), obj.getWorldLocation())))
			{
				addMenuEntries(obj, entry.getTarget(), offered);
			}
		}

		// Wall/decorative/ground objects usually have no Examine entry — offer them
		// via the hovered tile instead.
		final Tile tile = wv.getSelectedSceneTile();
		if (tile == null)
		{
			return;
		}
		for (TileObject obj : tileObjects(tile))
		{
			if (obj instanceof GameObject || !covered.add(tileKey(obj.getId(), obj.getWorldLocation())))
			{
				continue;
			}
			final String name = resolveName(obj.getId());
			if (name != null)
			{
				addMenuEntries(obj, "<col=ffff>" + name + "</col>", offered);
			}
		}
	}

	private void addMenuEntries(TileObject obj, String target, Set<HideEntry> offered)
	{
		final String name = resolveName(obj.getId());
		// The compliance gate: unnamed objects can never be hidden, and a hardcoded
		// safety list is blocked two ways — directly by object id (robust, no
		// timing dependency) and by resolved name (also covers imports).
		if (name == null || BANNED_OBJECT_IDS.contains(obj.getId()) || bannedNames.contains(name))
		{
			return;
		}

		final HideEntry tileEntry = tileEntryFor(obj, name);
		final HideEntry areaEntry = areaEntryFor(obj, name);
		final HideEntry globalEntry = globalEntryFor(name);

		if (config.revealAll())
		{
			// Narrowest reach first, so the closest-matching Unhide is on top.
			// Unhide ignores the menu-reach trim: an existing hide must always
			// be removable in-world, whatever reaches the user offers for hiding.
			addUnhide(tileEntry, UNHIDE_ONE, target, offered);
			addUnhide(areaEntry, UNHIDE_AREA, target, offered);
			addUnhide(globalEntry, UNHIDE_ALL, target, offered);
			return;
		}
		final MenuReach reach = config.menuReach();
		addHideOption(tileEntry, HIDE_ONE, target, offered);
		if (reach != MenuReach.TILE_ONLY)
		{
			addHideOption(areaEntry, HIDE_AREA, target, offered);
		}
		if (reach == MenuReach.ALL)
		{
			addHideOption(globalEntry, HIDE_ALL, target, offered);
		}
	}

	private void addHideOption(HideEntry entry, String option, String target, Set<HideEntry> offered)
	{
		if (!anyGroupContains(entry) && offered.add(entry))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption(option)
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> addHide(entry));
		}
	}

	private void addUnhide(HideEntry entry, String option, String target, Set<HideEntry> offered)
	{
		if (anyGroupContains(entry) && offered.add(entry))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption(option)
				.setTarget(target)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> removeHideEverywhere(entry));
		}
	}

	private HideEntry tileEntryFor(TileObject obj, String name)
	{
		final WorldPoint tp = toTemplateWorldPoint(obj);
		final HideEntry e = new HideEntry();
		e.setObjectName(name);
		e.setScope(HideScope.TILE);
		e.setRegionId(tp.getRegionID());
		e.setRegionX(tp.getRegionX());
		e.setRegionY(tp.getRegionY());
		e.setPlane(tp.getPlane());
		e.setInstance(obj.getWorldView().isInstance());
		return e;
	}

	private HideEntry areaEntryFor(TileObject obj, String name)
	{
		final WorldPoint tp = toTemplateWorldPoint(obj);
		final HideEntry e = new HideEntry();
		e.setObjectName(name);
		e.setScope(HideScope.AREA);
		e.setRegionId(tp.getRegionID());
		e.setInstance(obj.getWorldView().isInstance());
		return e;
	}

	private static HideEntry globalEntryFor(String name)
	{
		final HideEntry e = new HideEntry();
		e.setObjectName(name);
		e.setScope(HideScope.GLOBAL);
		return e;
	}

	// --- hide / unhide ---------------------------------------------------------------

	private void addHide(HideEntry entry)
	{
		if (bannedNames.contains(entry.getObjectName()))
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Hiding this object is disallowed due to plugin hub rules.", null);
			return;
		}
		maybeExpireActiveGroup();
		synchronized (this)
		{
			final HideGroup group = activeGroup();
			group.getEntries().add(entry);
			lastHide = entry;
			lastHideGroup = group.getName();
		}
		touchActiveGroup();
		saveToConfig();
		if (config.chatFeedback())
		{
			// Client thread (menu click), so addChatMessage is safe here
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Better Object Hider: Hidden \"" + entry.getObjectName() + "\" — "
					+ LocationLabel.describe(entry) + ". Undo from the side panel.", null);
		}
	}

	/**
	 * The panel's undo-row text for the most recent in-game hide, or null when
	 * there is nothing to undo (no hide yet, already undone, or the entry no
	 * longer sits in the group it was added to). The label is built outside the
	 * monitor — it does a data-table scan the client thread must not wait on.
	 */
	public String getUndoText()
	{
		final HideEntry entry;
		synchronized (this)
		{
			if (!lastHideValid())
			{
				return null;
			}
			entry = lastHide;
		}
		return "\"" + entry.getObjectName() + "\" — " + LocationLabel.describe(entry);
	}

	/** Reverts the most recent in-game hide — from the group it was added to only. */
	public void undoLastHide()
	{
		final HideEntry entry;
		final String group;
		synchronized (this)
		{
			entry = lastHide;
			group = lastHideGroup;
			lastHide = null;
			lastHideGroup = null;
		}
		if (entry != null && group != null)
		{
			removeEntry(group, entry);
		}
	}

	/** Must be called inside synchronized(this). */
	private boolean lastHideValid()
	{
		if (lastHide == null || lastHideGroup == null)
		{
			return false;
		}
		final HideGroup group = findGroup(groups, lastHideGroup);
		return group != null && group.getEntries().contains(lastHide);
	}

	/** Panel reveal-eye toggle; the config change flows back through onConfigChanged. */
	public void toggleRevealAll()
	{
		configManager.setConfiguration(CONFIG_GROUP, "revealAll", !config.revealAll());
	}

	/** Reveal-mode / panel unhide: removes the entry from every group so it reappears. */
	public void removeHideEverywhere(HideEntry entry)
	{
		synchronized (this)
		{
			for (HideGroup group : groups)
			{
				group.getEntries().remove(entry);
			}
		}
		saveToConfig();
	}

	public void removeEntry(String groupName, HideEntry entry)
	{
		synchronized (this)
		{
			final HideGroup group = findGroup(groups, groupName);
			if (group != null)
			{
				group.getEntries().remove(entry);
			}
		}
		saveToConfig();
	}

	/** Drag-and-drop: moves an entry from one group to another. */
	public void moveEntry(String fromGroup, String toGroup, HideEntry entry)
	{
		final boolean moved;
		synchronized (this)
		{
			moved = moveEntry(groups, fromGroup, toGroup, entry);
		}
		if (moved)
		{
			saveToConfig();
		}
	}

	private synchronized boolean anyGroupContains(HideEntry entry)
	{
		for (HideGroup group : groups)
		{
			if (group.getEntries().contains(entry))
			{
				return true;
			}
		}
		return false;
	}

	// --- name resolution (client thread only) ----------------------------------------

	/**
	 * The object's in-game name, impostor-unwrapped, or {@code null} when the
	 * object has no usable name (the game's {@code "null"} sentinel, empty, or an
	 * undefined composition). Client-thread only — {@code getObjectDefinition} is
	 * not safe off it.
	 */
	private String resolveName(int objectId)
	{
		ObjectComposition comp = client.getObjectDefinition(objectId);
		if (comp == null)
		{
			return null;
		}
		if (comp.getImpostorIds() != null)
		{
			final ObjectComposition impostor = comp.getImpostor();
			if (impostor != null)
			{
				comp = impostor;
			}
		}
		return cleanName(comp.getName());
	}

	/** Strips markup (defensive; game names are clean) and rejects the empty/"null" sentinels. */
	static String cleanName(String rawName)
	{
		if (rawName == null)
		{
			return null;
		}
		final String name = rawName.replaceAll("[<>]", "").trim();
		return name.isEmpty() || "null".equals(name) ? null : name;
	}

	private void resolveBannedNames()
	{
		for (int id : BANNED_OBJECT_IDS)
		{
			final String name = resolveName(id);
			if (name != null)
			{
				bannedNames.add(name);
			}
		}
	}

	// --- group management ------------------------------------------------------------

	/** The group new hides land in. Never null: invariants guarantee ≥1 group. */
	private HideGroup activeGroup()
	{
		final HideGroup group = findGroup(groups, activeGroupName);
		return group != null ? group : groups.get(0);
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
					def.getEntries().clear();
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

	/**
	 * Renames a group. The Default group keeps its name — it anchors the timeout
	 * fallback.
	 *
	 * @return the final name (possibly sanitized/uniquified), or null if nothing changed
	 */
	public String renameGroup(String oldName, String newName)
	{
		final String name;
		synchronized (this)
		{
			if (DEFAULT_GROUP_NAME.equals(oldName))
			{
				return null;
			}
			final HideGroup group = findGroup(groups, oldName);
			if (group == null)
			{
				return null;
			}
			final Set<String> taken = existingNames(groups);
			taken.remove(oldName);
			name = uniqueName(sanitizeName(newName), taken);
			if (name.equals(oldName))
			{
				return null;
			}
			group.setName(name);
			if (activeGroupName.equals(oldName))
			{
				activeGroupName = name;
			}
			if (oldName.equals(lastHideGroup))
			{
				lastHideGroup = name;
			}
		}
		saveToConfig();
		return name;
	}

	public void setGroupEnabled(String name, boolean enabled)
	{
		synchronized (this)
		{
			final HideGroup group = findGroup(groups, name);
			if (group != null)
			{
				group.setEnabled(enabled);
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
		configManager.setConfiguration(CONFIG_GROUP, ACTIVE_KEY, name);
	}

	/** Must be called inside synchronized(this). */
	private void ensureInvariants()
	{
		ensureDefaultGroup(groups);
		if (findGroup(groups, activeGroupName) == null)
		{
			activeGroupName = groups.get(0).getName();
		}
	}

	// --- active-group idle timeout ---------------------------------------------------

	/**
	 * If the active group hasn't been used within the configured timeout, new
	 * hides revert to Default — a selection forgotten for days must not silently
	 * collect stray hides. The timer refreshes on every hide and every explicit
	 * active-group pick, so it never resets mid-project.
	 */
	private void maybeExpireActiveGroup()
	{
		final String raw = configManager.getConfiguration(CONFIG_GROUP, LAST_USE_KEY);
		if (raw == null)
		{
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
		configManager.setConfiguration(CONFIG_GROUP, ACTIVE_KEY, target);
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
		if (findGroup(groups, DEFAULT_GROUP_NAME) != null)
		{
			return DEFAULT_GROUP_NAME;
		}
		return groups.isEmpty() ? DEFAULT_GROUP_NAME : groups.get(0).getName();
	}

	// --- import / export -------------------------------------------------------------

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

	public boolean isShowHelp()
	{
		return config.showHelp();
	}

	/** Called by the panel's help-box dismiss control. */
	public void dismissHelp()
	{
		configManager.setConfiguration(CONFIG_GROUP, "showHelp", false);
	}

	/**
	 * Copies the group's JSON to the clipboard. Feedback goes to game chat when
	 * logged in (like Ground Markers); otherwise the message is returned for a dialog.
	 *
	 * @return a message for the panel to show, or null if chat handled it
	 */
	public String exportGroupToClipboard(String groupName)
	{
		final String json;
		final int count;
		synchronized (this)
		{
			final HideGroup found = findGroup(groups, groupName);
			if (found == null)
			{
				return "Group not found.";
			}
			count = found.getEntries().size();
			json = gson.toJson(found);
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json), null);

		final String message = "Copied \"" + groupName + "\" (" + count + " objects) to the clipboard.";
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Better Object Hider: " + message, null));
			return null;
		}
		return message;
	}

	/** A parsed, sanitized clipboard group awaiting the user's confirmation. */
	public static final class ImportPreview
	{
		/** Ready-to-commit group, or null when {@link #error} is set. */
		public final HideGroup group;
		public final String error;

		ImportPreview(HideGroup group, String error)
		{
			this.group = group;
			this.error = error;
		}
	}

	/**
	 * Reads and sanitizes a hide group from the clipboard without adding it, so
	 * the panel can show a confirmation first (imported data is untrusted).
	 */
	public ImportPreview previewImportFromClipboard()
	{
		final String text;
		try
		{
			text = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString();
		}
		catch (IOException | UnsupportedFlavorException ex)
		{
			return new ImportPreview(null, "Unable to read the system clipboard.");
		}
		if (text.length() > MAX_IMPORT_CHARS)
		{
			return new ImportPreview(null, "The clipboard content is too large to be a hide group.");
		}

		HideGroup imported;
		try
		{
			imported = gson.fromJson(text, HideGroup.class);
		}
		catch (JsonSyntaxException ex)
		{
			log.debug("malformed import", ex);
			return new ImportPreview(null, "The clipboard does not contain a valid hide group.");
		}

		imported = sanitizeGroup(imported, bannedNames);
		if (imported == null || imported.getEntries().isEmpty())
		{
			return new ImportPreview(null, "The clipboard does not contain a valid hide group.");
		}
		if (imported.getEntries().size() > MAX_IMPORT_ENTRIES)
		{
			return new ImportPreview(null, "The hide group has too many objects (limit " + MAX_IMPORT_ENTRIES + ").");
		}
		return new ImportPreview(imported, null);
	}

	/**
	 * Adds a previewed group after the user confirmed the import. Sanitization
	 * runs again here so the guard is inseparable from the add — the argument
	 * must never be trusted to have come through the preview path.
	 */
	public ImportResult commitImport(HideGroup group)
	{
		final HideGroup imported = sanitizeGroup(group, bannedNames);
		if (imported == null || imported.getEntries().isEmpty()
			|| imported.getEntries().size() > MAX_IMPORT_ENTRIES)
		{
			return new ImportResult(false, "The hide group could not be imported.");
		}
		final String name;
		final int count = imported.getEntries().size();
		synchronized (this)
		{
			name = uniqueName(imported.getName(), existingNames(groups));
			imported.setName(name);
			groups.add(imported);
		}
		saveToConfig();
		return new ImportResult(true, "Imported \"" + name + "\" (" + count + " objects).");
	}

	// --- pure helpers (static, unit-tested) ------------------------------------------

	/** Whether an object matches a specific entry: same name, then scope-appropriate location. */
	static boolean matchesEntry(HideEntry e, String name, int regionId, int regionX, int regionY, int plane, boolean instance)
	{
		if (!name.equals(e.getObjectName()))
		{
			return false;
		}
		switch (e.getScope())
		{
			case GLOBAL:
				return true;
			case AREA:
				return e.isInstance() == instance && e.getRegionId() == regionId;
			case TILE:
			default:
				return e.isInstance() == instance && e.getRegionId() == regionId
					&& e.getRegionX() == regionX && e.getRegionY() == regionY && e.getPlane() == plane;
		}
	}

	/** Union of entries over enabled groups. */
	static Set<HideEntry> effectiveEntries(Collection<HideGroup> groups)
	{
		final Set<HideEntry> out = new HashSet<>();
		for (HideGroup group : groups)
		{
			if (group.isEnabled())
			{
				out.addAll(group.getEntries());
			}
		}
		return out;
	}

	static Set<String> entryNames(Collection<HideEntry> entries)
	{
		final Set<String> out = new HashSet<>();
		for (HideEntry e : entries)
		{
			out.add(e.getObjectName());
		}
		return out;
	}

	/**
	 * Cleans an untrusted group (import or config): sane group name, and only
	 * entries with a real, non-banned object name. Rebuilds each entry so no
	 * unexpected fields survive. {@code bannedNames} may be empty.
	 */
	static HideGroup sanitizeGroup(HideGroup group, Set<String> bannedNames)
	{
		if (group == null)
		{
			return null;
		}
		final HideGroup out = new HideGroup();
		out.setName(sanitizeName(group.getName()));
		out.setEnabled(group.isEnabled());
		if (group.getEntries() != null)
		{
			for (HideEntry e : group.getEntries())
			{
				if (e == null)
				{
					continue;
				}
				final String name = cleanName(e.getObjectName());
				// scope == null means a pre-scope legacy record: drop it (clean break)
				if (name == null || e.getScope() == null || bannedNames.contains(name))
				{
					continue;
				}
				final HideEntry clean = normalizeEntry(name, e);
				if (clean != null)
				{
					out.getEntries().add(clean);
				}
			}
		}
		return out;
	}

	/**
	 * Rebuilds an entry keeping only the fields its scope actually uses, zeroing
	 * the rest. Equality is over all fields (Lombok {@code @Data}), so junk in
	 * unused fields would otherwise defeat de-duplication, the Unhide menu test,
	 * and removal-by-equality. Menu-built entries are already in this form.
	 *
	 * @return the normalized entry, or null when a used field is out of range
	 */
	static HideEntry normalizeEntry(String name, HideEntry e)
	{
		final HideEntry clean = new HideEntry();
		clean.setObjectName(name);
		clean.setScope(e.getScope());
		switch (e.getScope())
		{
			case GLOBAL:
				break;
			case AREA:
				if (e.getRegionId() < 0 || e.getRegionId() > 0xffff)
				{
					return null;
				}
				clean.setRegionId(e.getRegionId());
				clean.setInstance(e.isInstance());
				break;
			case TILE:
			default:
				if (e.getRegionId() < 0 || e.getRegionId() > 0xffff
					|| e.getRegionX() < 0 || e.getRegionX() >= Constants.REGION_SIZE
					|| e.getRegionY() < 0 || e.getRegionY() >= Constants.REGION_SIZE
					|| e.getPlane() < 0 || e.getPlane() >= Constants.MAX_Z)
				{
					return null;
				}
				clean.setRegionId(e.getRegionId());
				clean.setRegionX(e.getRegionX());
				clean.setRegionY(e.getRegionY());
				clean.setPlane(e.getPlane());
				clean.setInstance(e.isInstance());
				break;
		}
		return clean;
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
		return trimmed.length() <= MAX_GROUP_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_GROUP_NAME_LENGTH);
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
	 * so a profile that lost it self-heals at the next sync/login.
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

	/** @return true if the entry existed in the source group and was moved */
	static boolean moveEntry(Collection<HideGroup> groups, String fromGroup, String toGroup, HideEntry entry)
	{
		final HideGroup from = findGroup(groups, fromGroup);
		final HideGroup to = findGroup(groups, toGroup);
		if (from == null || to == null || from == to || !from.getEntries().remove(entry))
		{
			return false;
		}
		to.getEntries().add(entry);
		return true;
	}

	/**
	 * Parses the persisted groups JSON. A bad config value must never break
	 * startUp(), so malformed input falls back to a single empty Default group.
	 */
	static List<HideGroup> parseGroups(Gson gson, String json, Set<String> bannedNames)
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
				final HideGroup clean = sanitizeGroup(group, bannedNames);
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

	// --- object / scene helpers ------------------------------------------------------

	private boolean matchesAny(Set<HideEntry> effective, TileObject obj, String name)
	{
		final WorldPoint tp = toTemplateWorldPoint(obj);
		final boolean instance = obj.getWorldView().isInstance();
		for (HideEntry e : effective)
		{
			if (matchesEntry(e, name, tp.getRegionID(), tp.getRegionX(), tp.getRegionY(), tp.getPlane(), instance))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * De-instances the tile that {@link TileObject#getWorldLocation()} refers to,
	 * so a hide made inside an instance stores a stable template location and
	 * survives instance regeneration. Client-thread only. Keyed off
	 * getWorldLocation (not getLocalLocation) so hide-time and match-time agree
	 * for multi-tile objects.
	 */
	private WorldPoint toTemplateWorldPoint(TileObject obj)
	{
		final WorldView wv = obj.getWorldView();
		final WorldPoint wp = obj.getWorldLocation();
		final LocalPoint lp = LocalPoint.fromScene(wp.getX() - wv.getBaseX(), wp.getY() - wv.getBaseY(), wv);
		return WorldPoint.fromLocalInstance(client, lp, wp.getPlane());
	}

	/**
	 * Packed render-match key in the object's world-location space: object id in
	 * bits 34+, plane in bits 32-33, then x and y as 16-bit fields (world coords
	 * top out at 16383, so components can never bleed into each other). A long
	 * instead of a string spares {@link #drawObject} the string building of the
	 * old "id:x:y:plane" keys during zone uploads on the maploader thread (the
	 * Set lookup still boxes one Long per call — cheap, but not allocation-free).
	 */
	static long tileKey(int objectId, WorldPoint wp)
	{
		return ((long) objectId << 34)
			| ((long) wp.getPlane() << 32)
			| ((long) wp.getX() << 16)
			| wp.getY();
	}

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

	// --- GPU zone invalidation (client thread) ---------------------------------------

	/**
	 * Invalidates the zones of every scene object whose position is in the given
	 * set, so they re-upload with the current suppression state. Only zones where
	 * a matching object is found are touched — those are guaranteed initialized,
	 * avoiding the {@code assert zone.initialized} crash.
	 */
	private void invalidateZones(Set<Long> positions)
	{
		if (positions.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
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
						if (positions.contains(tileKey(obj.getId(), obj.getWorldLocation())))
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
		final int zx = (sceneX >> 3) + ZONE_OFFSET;
		final int zz = (sceneY >> 3) + ZONE_OFFSET;
		final long key = ((long) zx << 32) | zz;
		if (done.add(key))
		{
			dc.invalidateZone(scene, zx, zz);
		}
	}

	/** Invalidates the single zone containing one just-matched spawned object. Client thread. */
	private void invalidateObjectZone(TileObject obj)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final WorldView wv = client.getTopLevelWorldView();
		final DrawCallbacks dc = client.getDrawCallbacks();
		if (wv != null && dc != null)
		{
			invalidateZoneForObject(wv.getScene(), dc, obj, new HashSet<>());
		}
	}

	// --- panel accessors -------------------------------------------------------------

	/** Deep-ish snapshot for the EDT: safe to iterate while groups mutate. */
	public synchronized List<HideGroup> getGroupsSnapshot()
	{
		final List<HideGroup> out = new ArrayList<>(groups.size());
		for (HideGroup group : groups)
		{
			final HideGroup copy = new HideGroup();
			copy.setName(group.getName());
			copy.setEnabled(group.isEnabled());
			copy.getEntries().addAll(group.getEntries());
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

	// --- config (de)serialization ----------------------------------------------------

	private void loadFromConfig()
	{
		final List<HideGroup> parsed = parseGroups(gson, config.groups(), bannedNames);
		final Set<HideEntry> effective;
		synchronized (this)
		{
			groups.clear();
			groups.addAll(parsed);
			activeGroupName = config.activeGroup();
			ensureInvariants();
			effective = effectiveEntries(groups);
		}
		// Publish the caches the scene scan and spawn handlers read lock-free
		effectiveEntries = effective;
		effectiveNames = entryNames(effective);
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
		// Writing config fires onConfigChanged, which re-syncs, rebuilds the
		// hidden-position set, invalidates zones, and rebuilds the panel.
		configManager.setConfiguration(CONFIG_GROUP, GROUPS_KEY, json);
		configManager.setConfiguration(CONFIG_GROUP, ACTIVE_KEY, active);
	}
}
