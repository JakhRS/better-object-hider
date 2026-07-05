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
	static final String DEFAULT_GROUP_NAME = "Default";
	static final int MAX_GROUP_NAME_LENGTH = 40;

	private static final String HIDE_ONE = "Hide this one";
	private static final String HIDE_ID = "Hide all of ID";
	private static final String UNHIDE_ONE = "Unhide this one";
	private static final String UNHIDE_ID = "Unhide all of ID";

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
	// Object names resolved on the client thread, read by the panel on the EDT
	private final Map<Integer, String> objectNames = new ConcurrentHashMap<>();

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
		synchronized (this)
		{
			groups.clear();
		}
		activeHiddenIds.clear();
		activeHiddenTilePoints.clear();
		clientThread.invokeLater(() -> invalidateZones(ids, points));
	}

	// --- RenderCallback ----------------------------------------------------------

	@Override
	public boolean drawObject(Scene scene, TileObject object)
	{
		if (!isHidden(object))
		{
			return true;
		}
		// In reveal mode hidden objects stay visible so they can be unhidden
		return config.revealAll();
	}

	private boolean isHidden(TileObject object)
	{
		final int id = object.getId();
		if (activeHiddenIds.contains(id))
		{
			return true;
		}
		return !activeHiddenTilePoints.isEmpty()
			&& activeHiddenTilePoints.contains(tileKey(id, object.getWorldLocation()));
	}

	// --- lifecycle / sync ----------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			refresh();
		}
	}

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event)
	{
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
		// Union of old and new tile points so both newly hidden and newly
		// revealed objects get their zones re-uploaded
		final Set<String> points = new HashSet<>(activeHiddenTilePoints);
		rebuildActiveTilePoints();
		points.addAll(activeHiddenTilePoints);
		invalidateZones(idsToInvalidate, points);
		resolveObjectNames();
		rebuildPanel();
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

		final WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		final Set<String> tiles;
		synchronized (this)
		{
			tiles = effectiveTiles(groups);
		}
		for (String entry : tiles)
		{
			final int[] parts = parseTileEntry(entry);
			if (parts == null)
			{
				continue;
			}
			final WorldPoint wp = WorldPoint.fromRegion(parts[1], parts[2], parts[3], parts[4]);
			for (WorldPoint occurrence : WorldPoint.toLocalInstance(wv, wp))
			{
				activeHiddenTilePoints.add(tileKey(parts[0], occurrence));
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

		if (config.revealAll())
		{
			final String entry = regionKey(objectId, toTemplateWorldPoint(obj));
			if (isTileEntryHidden(entry))
			{
				client.getMenu().createMenuEntry(-1)
					.setOption(UNHIDE_ONE)
					.setTarget(target)
					.setType(MenuAction.RUNELITE)
					.onClick(e -> unhideTileEverywhere(entry));
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
		final String entry = regionKey(obj.getId(), toTemplateWorldPoint(obj));
		synchronized (this)
		{
			activeGroup().getTiles().add(entry);
		}
		objectNames.computeIfAbsent(obj.getId(), this::lookupName);
		saveToConfig();
	}

	private void hideAllOfId(int objectId)
	{
		if (rejectIfBanned(objectId))
		{
			return;
		}
		synchronized (this)
		{
			activeGroup().getIds().add(objectId);
		}
		objectNames.computeIfAbsent(objectId, this::lookupName);
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

	public void deleteGroup(String name)
	{
		synchronized (this)
		{
			groups.removeIf(g -> g.getName().equals(name));
			ensureInvariants();
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

	/** Must be called inside synchronized(this). */
	private void ensureInvariants()
	{
		if (groups.isEmpty())
		{
			final HideGroup def = new HideGroup();
			def.setName(DEFAULT_GROUP_NAME);
			groups.add(def);
		}
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

	/**
	 * Copies the group's JSON to the system clipboard.
	 *
	 * @return a user-facing status message
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
			count = found.getIds().size() + found.getTiles().size();
			json = gson.toJson(found);
		}
		Toolkit.getDefaultToolkit()
			.getSystemClipboard()
			.setContents(new StringSelection(json), null);
		return "Copied \"" + groupName + "\" (" + count + " entries) to the clipboard.";
	}

	/**
	 * Imports a group from JSON on the system clipboard. Sanitizes defensively:
	 * imported data is untrusted (nulls, garbage entries, banned IDs).
	 *
	 * @return a user-facing status message
	 */
	public String importGroupFromClipboard()
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
			return "Unable to read the system clipboard.";
		}

		HideGroup imported;
		try
		{
			imported = gson.fromJson(text, HideGroup.class);
		}
		catch (JsonSyntaxException ex)
		{
			log.debug("malformed import", ex);
			return "The clipboard does not contain a valid hide group.";
		}

		imported = sanitizeGroup(imported);
		if (imported == null || (imported.getIds().isEmpty() && imported.getTiles().isEmpty()))
		{
			return "The clipboard does not contain a valid hide group.";
		}

		final String name;
		final int count = imported.getIds().size() + imported.getTiles().size();
		synchronized (this)
		{
			name = uniqueName(imported.getName(), existingNames(groups));
			imported.setName(name);
			groups.add(imported);
		}
		saveToConfig();
		return "Imported \"" + name + "\" (" + count + " entries).";
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
		return out;
	}

	static String sanitizeName(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return DEFAULT_GROUP_NAME;
		}
		final String trimmed = name.trim();
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

	/**
	 * Parses a persisted "id:regionId:regionX:regionY:plane" entry.
	 *
	 * @return {id, regionId, regionX, regionY, plane}, or null if malformed
	 */
	public static int[] parseTileEntry(String entry)
	{
		final String[] parts = entry.split(":");
		if (parts.length != 5)
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
	private void invalidateZones(Set<Integer> ids, Set<String> tilePoints)
	{
		if ((ids.isEmpty() && tilePoints.isEmpty()) || client.getGameState() != GameState.LOGGED_IN)
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
							|| tilePoints.contains(tileKey(obj.getId(), obj.getWorldLocation())))
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
