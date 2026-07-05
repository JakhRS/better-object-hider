/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider.ui;

import com.betterobjecthider.BetterObjectHiderPlugin;
import com.betterobjecthider.HideGroup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.ImageUtil;

public class BetterObjectHiderPanel extends PluginPanel
{
	private static final ImageIcon CHEVRON_RIGHT;
	private static final ImageIcon CHEVRON_DOWN;
	private static final ImageIcon ACTIVE_ON;
	private static final ImageIcon ACTIVE_OFF;
	private static final ImageIcon ACTIVE_OFF_HOVER;
	private static final ImageIcon EXPORT;
	private static final ImageIcon EXPORT_HOVER;
	private static final ImageIcon DELETE;
	private static final ImageIcon DELETE_HOVER;

	static
	{
		final BufferedImage chevronRight = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "chevron_right.png");
		final BufferedImage chevronDown = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "chevron_down.png");
		final BufferedImage activeOn = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "active_on.png");
		final BufferedImage activeOff = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "active_off.png");
		final BufferedImage export = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "export.png");
		final BufferedImage delete = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "delete.png");

		CHEVRON_RIGHT = new ImageIcon(chevronRight);
		CHEVRON_DOWN = new ImageIcon(chevronDown);
		ACTIVE_ON = new ImageIcon(activeOn);
		ACTIVE_OFF = new ImageIcon(ImageUtil.alphaOffset(activeOff, -80));
		ACTIVE_OFF_HOVER = new ImageIcon(activeOff);
		EXPORT = new ImageIcon(ImageUtil.alphaOffset(export, -80));
		EXPORT_HOVER = new ImageIcon(export);
		DELETE = new ImageIcon(ImageUtil.alphaOffset(delete, -80));
		DELETE_HOVER = new ImageIcon(delete);
	}

	private final BetterObjectHiderPlugin plugin;
	private final JPanel listPanel = new JPanel(new GridBagLayout());
	private final PluginErrorPanel emptyPanel = new PluginErrorPanel();
	// Survives rebuild(): expanded/collapsed per group name (default expanded)
	private final Map<String, Boolean> expandedState = new HashMap<>();

	// Drag-and-drop state: rows can be dropped onto group headers to move hides
	private final List<JPanel> headerPanels = new ArrayList<>();
	private boolean dragging;
	private JPanel highlightedHeader;

	public BetterObjectHiderPanel(BetterObjectHiderPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));

		final JLabel title = new JLabel("Better Object Hider");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		final JButton newGroupButton = new JButton("New group");
		newGroupButton.setToolTipText("Create a new hide group");
		newGroupButton.addActionListener(e -> promptNewGroup());

		final JButton importButton = new JButton("Import");
		importButton.setToolTipText("Import a hide group from the clipboard");
		importButton.addActionListener(e ->
			JOptionPane.showMessageDialog(this, plugin.importGroupFromClipboard()));

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.add(newGroupButton);
		buttons.add(importButton);

		final JPanel northPanel = new JPanel(new BorderLayout(0, 8));
		northPanel.setBorder(new EmptyBorder(1, 0, 10, 0));
		northPanel.add(title, BorderLayout.NORTH);
		northPanel.add(buttons, BorderLayout.CENTER);

		emptyPanel.setContent("No hidden objects",
			"Shift+right-click a game object to hide it.");

		final JPanel centerPanel = new JPanel(new BorderLayout());
		centerPanel.add(listPanel, BorderLayout.NORTH);

		add(northPanel, BorderLayout.NORTH);
		add(centerPanel, BorderLayout.CENTER);
	}

	/** Repopulates the panel from the plugin's current state. Call on the EDT. */
	public void rebuild()
	{
		final GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		c.gridx = 0;
		c.gridy = 0;

		listPanel.removeAll();
		headerPanels.clear();

		final List<HideGroup> groups = plugin.getGroupsSnapshot();
		final String activeName = plugin.getActiveGroupName();

		int totalEntries = 0;
		for (final HideGroup group : groups)
		{
			totalEntries += group.getIds().size() + group.getTiles().size();
			final boolean expanded = expandedState.getOrDefault(group.getName(), true);

			final JPanel header = buildGroupHeader(group, group.getName().equals(activeName), expanded);
			headerPanels.add(header);
			listPanel.add(header, c);
			c.gridy++;

			if (expanded)
			{
				for (final JPanel row : buildGroupRows(group))
				{
					listPanel.add(row, c);
					c.gridy++;
				}
			}
			listPanel.add(Box.createRigidArea(new Dimension(0, 8)), c);
			c.gridy++;
		}

		// Hint for the fresh-install state: one pristine group, nothing hidden yet
		final boolean pristine = totalEntries == 0 && groups.size() == 1;
		emptyPanel.setVisible(pristine);
		listPanel.add(emptyPanel, c);

		revalidate();
		repaint();
	}

	// --- group header ------------------------------------------------------------------

	private JPanel buildGroupHeader(HideGroup group, boolean active, boolean expanded)
	{
		final String name = group.getName();
		final int count = group.getIds().size() + group.getTiles().size();

		final JPanel header = new JPanel(new BorderLayout());
		header.setBorder(new EmptyBorder(6, 6, 6, 6));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.putClientProperty("bohGroupName", name);

		final JLabel nameLabel = new JLabel(name + " (" + count + ")");
		nameLabel.setIcon(expanded ? CHEVRON_DOWN : CHEVRON_RIGHT);
		nameLabel.setIconTextGap(6);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(active ? ColorScheme.BRAND_ORANGE : Color.WHITE);
		nameLabel.setToolTipText(active
			? "Active group — new hides are added here. Click to collapse/expand."
			: "Click to collapse/expand");
		nameLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				expandedState.put(name, !expanded);
				rebuild();
			}
		});

		final JPanel controls = new JPanel();
		controls.setOpaque(false);
		controls.setLayout(new GridLayout(1, 0, 6, 0));

		final JCheckBox enabledBox = new JCheckBox();
		enabledBox.setSelected(group.isEnabled());
		enabledBox.setOpaque(false);
		enabledBox.setToolTipText("Enable/disable this group's hides");
		enabledBox.addActionListener(e -> plugin.setGroupEnabled(name, enabledBox.isSelected()));
		controls.add(enabledBox);

		if (!active)
		{
			controls.add(iconLabel(ACTIVE_OFF, ACTIVE_OFF_HOVER,
				"Set as active group (new hides land here)", () -> plugin.setActiveGroup(name)));
		}
		else
		{
			final JLabel activeDot = new JLabel(ACTIVE_ON);
			activeDot.setToolTipText("Active group");
			controls.add(activeDot);
		}

		controls.add(iconLabel(EXPORT, EXPORT_HOVER, "Export group to clipboard",
			() -> JOptionPane.showMessageDialog(this, plugin.exportGroupToClipboard(name))));

		controls.add(iconLabel(DELETE, DELETE_HOVER, "Delete group", () ->
		{
			final int confirm = JOptionPane.showConfirmDialog(this,
				"Delete group \"" + name + "\" and unhide its " + count + " entries?",
				"Delete group", JOptionPane.OK_CANCEL_OPTION);
			if (confirm == JOptionPane.OK_OPTION)
			{
				plugin.deleteGroup(name);
			}
		}));

		header.add(nameLabel, BorderLayout.CENTER);
		header.add(controls, BorderLayout.EAST);
		return header;
	}

	// --- group body --------------------------------------------------------------------

	private List<JPanel> buildGroupRows(HideGroup group)
	{
		final List<JPanel> rows = new ArrayList<>();
		final String groupName = group.getName();

		// "Hide all of ID" entries
		final List<Integer> sortedIds = new ArrayList<>(group.getIds());
		sortedIds.sort(null);
		for (final int objectId : sortedIds)
		{
			rows.add(buildRow(plugin.getObjectName(objectId), "All of ID " + objectId, 0,
				() -> plugin.removeIdFromGroup(groupName, objectId),
				target -> plugin.moveIdToGroup(groupName, target, objectId)));
		}

		// Tile entries, collapsed per object ID
		final Map<Integer, List<String>> byId = new TreeMap<>();
		for (final String entry : group.getTiles())
		{
			final int[] parts = BetterObjectHiderPlugin.parseTileEntry(entry);
			if (parts != null)
			{
				byId.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(entry);
			}
		}
		for (final Map.Entry<Integer, List<String>> e : byId.entrySet())
		{
			final int objectId = e.getKey();
			final List<String> entries = e.getValue();
			entries.sort(null);

			if (entries.size() == 1)
			{
				final String entry = entries.get(0);
				rows.add(buildRow(plugin.getObjectName(objectId),
					tileDetail(entry) + " (ID " + objectId + ")", 0,
					() -> plugin.removeTileFromGroup(groupName, entry),
					target -> plugin.moveTileToGroup(groupName, target, entry)));
				continue;
			}

			// Same-type sub-header with a remove-all, then the individual tiles
			rows.add(buildRow(plugin.getObjectName(objectId) + " ×" + entries.size(),
				"ID " + objectId + " — remove all", 0,
				() -> plugin.removeTilesOfIdFromGroup(groupName, objectId),
				target -> plugin.moveTilesOfIdToGroup(groupName, target, objectId)));
			for (final String entry : entries)
			{
				rows.add(buildRow(tileDetail(entry), null, 12,
					() -> plugin.removeTileFromGroup(groupName, entry),
					target -> plugin.moveTileToGroup(groupName, target, entry)));
			}
		}

		if (rows.isEmpty())
		{
			final JPanel empty = new JPanel(new BorderLayout());
			empty.setBorder(new EmptyBorder(4, 8, 4, 8));
			empty.setBackground(ColorScheme.DARK_GRAY_COLOR);
			final JLabel label = new JLabel("(empty)");
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.add(label, BorderLayout.CENTER);
			rows.add(empty);
		}
		return rows;
	}

	private static String tileDetail(String entry)
	{
		final int[] parts = BetterObjectHiderPlugin.parseTileEntry(entry);
		if (parts == null)
		{
			return entry;
		}
		final WorldPoint wp = WorldPoint.fromRegion(parts[1], parts[2], parts[3], parts[4]);
		final StringBuilder sb = new StringBuilder("Tile ")
			.append(wp.getX()).append(", ").append(wp.getY());
		if (parts[4] != 0)
		{
			sb.append(", plane ").append(parts[4]);
		}
		return sb.toString();
	}

	// --- widgets -------------------------------------------------------------------------

	private JPanel buildRow(String name, String detail, int indent, Runnable onRemove, Consumer<String> moveToGroup)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBorder(new EmptyBorder(4, 8 + indent, 4, 8));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setToolTipText("Drag onto a group header to move");

		final JPanel text = new JPanel(new GridLayout(0, 1));
		text.setOpaque(false);

		final JLabel nameLabel = new JLabel(name);
		nameLabel.setForeground(Color.WHITE);
		text.add(nameLabel);

		if (detail != null)
		{
			final JLabel detailLabel = new JLabel(detail);
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			text.add(detailLabel);
		}

		row.add(text, BorderLayout.CENTER);
		row.add(iconLabel(DELETE, DELETE_HOVER, "Unhide", onRemove), BorderLayout.EAST);

		// Drag from anywhere on the row (except the delete icon, which owns its
		// own listener) onto another group's header to move the entry there.
		final MouseAdapter drag = new DragHandler(moveToGroup);
		for (Component target : new Component[]{row, text, nameLabel})
		{
			target.addMouseListener(drag);
			target.addMouseMotionListener(drag);
		}
		return row;
	}

	private final class DragHandler extends MouseAdapter
	{
		private final Consumer<String> moveToGroup;
		private Point pressPoint;

		private DragHandler(Consumer<String> moveToGroup)
		{
			this.moveToGroup = moveToGroup;
		}

		@Override
		public void mousePressed(MouseEvent e)
		{
			pressPoint = e.getPoint();
		}

		@Override
		public void mouseDragged(MouseEvent e)
		{
			if (pressPoint == null)
			{
				return;
			}
			if (!dragging && e.getPoint().distance(pressPoint) > 5)
			{
				dragging = true;
				setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
			}
			if (dragging)
			{
				highlightHeader(headerAt(toListPanel(e)));
			}
		}

		@Override
		public void mouseReleased(MouseEvent e)
		{
			if (dragging)
			{
				final JPanel header = headerAt(toListPanel(e));
				highlightHeader(null);
				setCursor(Cursor.getDefaultCursor());
				dragging = false;
				if (header != null)
				{
					final String target = (String) header.getClientProperty("bohGroupName");
					moveToGroup.accept(target);
				}
			}
			pressPoint = null;
		}

		private Point toListPanel(MouseEvent e)
		{
			return SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), listPanel);
		}
	}

	private JPanel headerAt(Point listPanelPoint)
	{
		for (JPanel header : headerPanels)
		{
			if (header.getBounds().contains(listPanelPoint))
			{
				return header;
			}
		}
		return null;
	}

	private void highlightHeader(JPanel header)
	{
		if (highlightedHeader == header)
		{
			return;
		}
		if (highlightedHeader != null)
		{
			highlightedHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		}
		highlightedHeader = header;
		if (header != null)
		{
			header.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		}
	}

	private static JLabel iconLabel(ImageIcon icon, ImageIcon hoverIcon, String tooltip, Runnable onClick)
	{
		final JLabel label = new JLabel(icon);
		label.setToolTipText(tooltip);
		label.setBorder(new EmptyBorder(0, 4, 0, 4));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setIcon(hoverIcon);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(icon);
			}
		});
		return label;
	}

	private void promptNewGroup()
	{
		final String name = JOptionPane.showInputDialog(this, "Group name:", "New group",
			JOptionPane.PLAIN_MESSAGE);
		if (name != null && !name.trim().isEmpty())
		{
			plugin.createGroup(name);
		}
	}
}
