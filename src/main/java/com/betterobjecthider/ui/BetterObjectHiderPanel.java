/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider.ui;

import com.betterobjecthider.BetterObjectHiderPlugin;
import com.betterobjecthider.EntryFilter;
import com.betterobjecthider.HideEntry;
import com.betterobjecthider.HideGroup;
import com.betterobjecthider.LocationLabel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

public class BetterObjectHiderPanel extends PluginPanel
{
	private static final String REPO_URL = "https://github.com/JakhRS/better-object-hider";
	private static final String KOFI_URL = "https://ko-fi.com/jakhrs";

	private static final ImageIcon CHEVRON_RIGHT;
	private static final ImageIcon CHEVRON_DOWN;
	private static final ImageIcon ACTIVE_ON;
	private static final ImageIcon ACTIVE_OFF;
	private static final ImageIcon ACTIVE_OFF_HOVER;
	private static final ImageIcon EXPORT;
	private static final ImageIcon EXPORT_HOVER;
	private static final ImageIcon DELETE;
	private static final ImageIcon DELETE_HOVER;
	private static final ImageIcon EYE_OPEN;
	private static final ImageIcon EYE_OPEN_HOVER;
	private static final ImageIcon EYE_CLOSED;
	private static final ImageIcon EYE_CLOSED_HOVER;
	private static final ImageIcon HEART;
	private static final ImageIcon HEART_HOVER;
	private static final ImageIcon CODE;
	private static final ImageIcon CODE_HOVER;
	private static final ImageIcon RENAME;
	private static final ImageIcon RENAME_HOVER;

	// Sort entries by object name, then reach (tile, area, global), then location
	private static final Comparator<HideEntry> ENTRY_ORDER = Comparator
		.comparing((HideEntry e) -> e.getObjectName().toLowerCase())
		.thenComparingInt(e -> e.getScope().ordinal())
		.thenComparingInt(HideEntry::getRegionId)
		.thenComparingInt(HideEntry::getRegionX)
		.thenComparingInt(HideEntry::getRegionY)
		.thenComparingInt(HideEntry::getPlane);

	// Local-JVM DnD flavor: the payload is passed as a live object, never serialized
	private static final DataFlavor DRAG_FLAVOR = new DataFlavor(DragPayload.class, "Better Object Hider drag payload");

	static
	{
		final BufferedImage chevronRight = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "chevron_right.png");
		final BufferedImage chevronDown = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "chevron_down.png");
		final BufferedImage activeOn = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "active_on.png");
		final BufferedImage activeOff = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "active_off.png");
		final BufferedImage export = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "export.png");
		final BufferedImage delete = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "delete.png");
		final BufferedImage eyeOpen = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "eye_open.png");
		final BufferedImage eyeClosed = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "eye_closed.png");
		final BufferedImage heart = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "heart.png");
		final BufferedImage code = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "code.png");
		final BufferedImage rename = ImageUtil.loadImageResource(BetterObjectHiderPanel.class, "rename.png");

		CHEVRON_RIGHT = new ImageIcon(chevronRight);
		CHEVRON_DOWN = new ImageIcon(chevronDown);
		ACTIVE_ON = new ImageIcon(activeOn);
		ACTIVE_OFF = new ImageIcon(ImageUtil.alphaOffset(activeOff, -80));
		ACTIVE_OFF_HOVER = new ImageIcon(activeOff);
		EXPORT = new ImageIcon(ImageUtil.alphaOffset(export, -80));
		EXPORT_HOVER = new ImageIcon(export);
		DELETE = new ImageIcon(ImageUtil.alphaOffset(delete, -80));
		DELETE_HOVER = new ImageIcon(delete);
		EYE_OPEN = new ImageIcon(eyeOpen);
		EYE_OPEN_HOVER = new ImageIcon(ImageUtil.alphaOffset(eyeOpen, -80));
		EYE_CLOSED = new ImageIcon(eyeClosed);
		EYE_CLOSED_HOVER = new ImageIcon(ImageUtil.alphaOffset(eyeClosed, -80));
		HEART = new ImageIcon(ImageUtil.alphaOffset(heart, -80));
		HEART_HOVER = new ImageIcon(heart);
		CODE = new ImageIcon(ImageUtil.alphaOffset(code, -80));
		CODE_HOVER = new ImageIcon(code);
		RENAME = new ImageIcon(ImageUtil.alphaOffset(rename, -80));
		RENAME_HOVER = new ImageIcon(rename);
	}

	private final BetterObjectHiderPlugin plugin;
	private final JPanel listPanel = new JPanel(new GridBagLayout());
	private final PluginErrorPanel emptyPanel = new PluginErrorPanel();
	private final IconTextField searchBar = new IconTextField();
	// Stateful title-row toggle; icon/tooltip refreshed in rebuild()
	private final JLabel revealToggle = new JLabel(EYE_CLOSED);
	// Sits between the title row and the buttons; rebuild() fills it with the
	// reveal banner while reveal mode is on, so the banner is the top item
	private final JPanel revealBannerSlot = new JPanel(new BorderLayout());
	// Survives rebuild(): expanded/collapsed per group name (default expanded)
	private final Map<String, Boolean> expandedState = new HashMap<>();
	// describe() is a pure function of the entry's (value-equal) fields but does
	// a data-table scan; rebuild() runs per keystroke, so cache labels across
	// rebuilds. Size-guarded rather than invalidated — stale entries are
	// impossible, only unused ones accumulate.
	private final Map<HideEntry, String> labelCache = new HashMap<>();

	private JPanel highlightedHeader;

	public BetterObjectHiderPanel(BetterObjectHiderPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));

		final JLabel title = new JLabel("Better Object Hider");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		final JPanel links = new JPanel(new GridLayout(1, 0, 8, 0));
		links.setOpaque(false);
		revealToggle.setHorizontalAlignment(JLabel.CENTER);
		revealToggle.setBorder(new EmptyBorder(0, 4, 0, 4));
		revealToggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.toggleRevealAll();
			}
		});
		links.add(revealToggle);
		links.add(iconLabel(CODE, CODE_HOVER, "Source code & issue tracker (GitHub)",
			() -> LinkBrowser.browse(REPO_URL)));
		links.add(iconLabel(HEART, HEART_HOVER, "Enjoying the plugin? Buy me a Bond (Ko-fi)",
			() -> LinkBrowser.browse(KOFI_URL)));

		final JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(links, BorderLayout.EAST);

		final JButton newGroupButton = new JButton("New group");
		newGroupButton.setToolTipText("Create a new hide group");
		newGroupButton.addActionListener(e -> promptNewGroup());

		final JButton importButton = new JButton("Import");
		importButton.setToolTipText("Import a hide group from the clipboard");
		importButton.addActionListener(e -> promptImport());

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.add(newGroupButton);
		buttons.add(importButton);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setToolTipText("<html>Filter hidden objects; * matches anything.<br>"
			+ "Prefixes: <b>area:</b>name, <b>group:</b>name,<br>"
			+ "<b>scope:</b>tile/area/everywhere, <b>is:</b>instance</html>");
		searchBar.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				rebuild();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				rebuild();
			}
		});

		searchBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		final JPanel northPanel = new JPanel();
		northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
		northPanel.setBorder(new EmptyBorder(1, 0, 10, 0));
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		revealBannerSlot.setAlignmentX(LEFT_ALIGNMENT);
		revealBannerSlot.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		searchBar.setAlignmentX(LEFT_ALIGNMENT);
		northPanel.add(titleRow);
		northPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		northPanel.add(revealBannerSlot);
		northPanel.add(buttons);
		northPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		northPanel.add(searchBar);

		emptyPanel.setContent("No hidden objects",
			"Shift+right-click a named object to hide it.");

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

		final boolean reveal = plugin.isRevealAll();
		revealToggle.setIcon(reveal ? EYE_OPEN : EYE_CLOSED);
		revealToggle.setToolTipText(reveal
			? "Reveal mode is on — hidden objects are visible. Click to re-hide."
			: "Reveal hidden objects so you can right-click them to unhide");
		revealBannerSlot.removeAll();
		if (reveal)
		{
			revealBannerSlot.add(buildRevealBanner(), BorderLayout.CENTER);
		}
		revealBannerSlot.setBorder(reveal ? new EmptyBorder(0, 0, 8, 0) : null);

		listPanel.removeAll();
		highlightedHeader = null;

		final List<HideGroup> groups = plugin.getGroupsSnapshot();
		final String activeName = plugin.getActiveGroupName();
		final EntryFilter filter = EntryFilter.parse(searchBar.getText());
		final boolean filtering = !filter.isEmpty();

		int totalEntries = 0;
		for (final HideGroup group : groups)
		{
			totalEntries += group.getEntries().size();
		}

		// Fresh-install state: one pristine group and nothing hidden — show only
		// the hint, not an empty group section competing with it
		final boolean pristine = totalEntries == 0 && groups.size() == 1;

		if (plugin.isShowHelp())
		{
			listPanel.add(buildHelpBox(), c);
			c.gridy++;
			listPanel.add(Box.createRigidArea(new Dimension(0, 8)), c);
			c.gridy++;
		}

		final String undoText = plugin.getUndoText();
		if (undoText != null)
		{
			listPanel.add(buildUndoRow(undoText), c);
			c.gridy++;
			listPanel.add(Box.createRigidArea(new Dimension(0, 8)), c);
			c.gridy++;
		}

		boolean anyShown = false;
		if (!pristine)
		{
			for (final HideGroup group : groups)
			{
				final List<JPanel> rows = buildGroupRows(group, filter);
				// A filter narrows the panel to matching rows; groups with none vanish
				if (filtering && rows.isEmpty())
				{
					continue;
				}
				anyShown = true;
				final boolean expanded = filtering || expandedState.getOrDefault(group.getName(), true);
				final int total = group.getEntries().size();
				final String countLabel = filtering ? rows.size() + " of " + total : String.valueOf(total);

				listPanel.add(buildGroupHeader(group, group.getName().equals(activeName), expanded, countLabel), c);
				c.gridy++;

				if (expanded)
				{
					for (final JPanel row : rows)
					{
						listPanel.add(row, c);
						c.gridy++;
					}
				}
				listPanel.add(Box.createRigidArea(new Dimension(0, 8)), c);
				c.gridy++;
			}
			if (filtering && !anyShown)
			{
				listPanel.add(buildNoMatches(), c);
				c.gridy++;
			}
		}

		emptyPanel.setVisible(pristine);
		listPanel.add(emptyPanel, c);

		revalidate();
		repaint();
	}

	private JPanel buildUndoRow(String undoText)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(new EmptyBorder(4, 8, 4, 4));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel label = new JLabel("Hidden " + undoText);
		label.putClientProperty("html.disable", Boolean.TRUE);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText("The most recent hide this session");

		final JButton undo = new JButton("Undo");
		undo.setFont(FontManager.getRunescapeSmallFont());
		undo.setToolTipText("Unhide it again");
		undo.addActionListener(e -> plugin.undoLastHide());

		row.add(label, BorderLayout.CENTER);
		row.add(undo, BorderLayout.EAST);
		return row;
	}

	private JPanel buildNoMatches()
	{
		final JPanel box = new JPanel(new BorderLayout());
		box.setBorder(new EmptyBorder(6, 8, 6, 8));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		final JLabel label = new JLabel("No hidden objects match the search.");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		box.add(label, BorderLayout.CENTER);
		return box;
	}

	private void promptImport()
	{
		final BetterObjectHiderPlugin.ImportPreview preview = plugin.previewImportFromClipboard();
		if (preview.error != null)
		{
			JOptionPane.showMessageDialog(this, preview.error, "Import hide group",
				JOptionPane.ERROR_MESSAGE);
			return;
		}
		final int count = preview.group.getEntries().size();
		final int confirm = JOptionPane.showConfirmDialog(this,
			"Import \"" + preview.group.getName() + "\" (" + count
				+ (count == 1 ? " hidden object" : " hidden objects") + ")?",
			"Import hide group", JOptionPane.OK_CANCEL_OPTION);
		if (confirm == JOptionPane.OK_OPTION)
		{
			plugin.commitImport(preview.group);
		}
	}

	private JPanel buildHelpBox()
	{
		final JPanel box = new JPanel(new BorderLayout(6, 0));
		box.setBorder(new EmptyBorder(6, 8, 6, 6));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel text = new JLabel("<html>Shift + right-click an object to hide it."
			+ " Create groups to organize your hides.</html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		final JLabel dismiss = iconLabel(DELETE, DELETE_HOVER, "Dismiss (re-enable in config)",
			plugin::dismissHelp);

		box.add(text, BorderLayout.CENTER);
		box.add(dismiss, BorderLayout.EAST);
		return box;
	}

	private JPanel buildRevealBanner()
	{
		final JPanel banner = new JPanel(new BorderLayout());
		banner.setBorder(new EmptyBorder(6, 8, 6, 8));
		banner.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel label = new JLabel("<html>Reveal mode is on — hidden objects are visible."
			+ " Click the eye icon above to re-hide.</html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		banner.add(label, BorderLayout.CENTER);
		return banner;
	}

	// --- group header ------------------------------------------------------------------

	private JPanel buildGroupHeader(HideGroup group, boolean active, boolean expanded, String countLabel)
	{
		final String name = group.getName();
		final int count = group.getEntries().size();

		// Two lines: full-width name (no truncation), then evenly-spaced controls
		final JPanel header = new JPanel(new BorderLayout(0, 4));
		header.setBorder(new EmptyBorder(6, 6, 6, 6));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Drop target: rows dragged here move into this group
		header.setTransferHandler(new TransferHandler()
		{
			@Override
			public boolean canImport(TransferSupport support)
			{
				if (!support.isDataFlavorSupported(DRAG_FLAVOR))
				{
					return false;
				}
				highlightHeader(header);
				return true;
			}

			@Override
			public boolean importData(TransferSupport support)
			{
				highlightHeader(null);
				try
				{
					final DragPayload payload = (DragPayload)
						support.getTransferable().getTransferData(DRAG_FLAVOR);
					payload.moveToGroup.accept(name);
					return true;
				}
				catch (UnsupportedFlavorException | java.io.IOException ex)
				{
					return false;
				}
			}
		});

		final JLabel nameLabel = new JLabel(name + " (" + countLabel + ")");
		// Defense in depth: never interpret an (untrusted) group name as HTML
		nameLabel.putClientProperty("html.disable", Boolean.TRUE);
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

		final boolean enabled = group.isEnabled();
		controls.add(iconLabel(
			enabled ? EYE_OPEN : EYE_CLOSED,
			enabled ? EYE_OPEN_HOVER : EYE_CLOSED_HOVER,
			enabled ? "Hides active — click to disable this group (its objects reappear)"
				: "Group disabled, objects visible — click to re-enable",
			() -> plugin.setGroupEnabled(name, !enabled)));

		if (!active)
		{
			controls.add(iconLabel(ACTIVE_OFF, ACTIVE_OFF_HOVER,
				"Set as active group (new hides land here)", () -> plugin.setActiveGroup(name)));
		}
		else
		{
			final JLabel activeDot = new JLabel(ACTIVE_ON);
			activeDot.setHorizontalAlignment(JLabel.CENTER);
			activeDot.setToolTipText("Active group");
			controls.add(activeDot);
		}

		final boolean isDefault = BetterObjectHiderPlugin.DEFAULT_GROUP_NAME.equals(name);
		if (!isDefault)
		{
			controls.add(iconLabel(RENAME, RENAME_HOVER, "Rename group", () -> promptRename(name)));
		}

		controls.add(iconLabel(EXPORT, EXPORT_HOVER, "Export group to clipboard", () ->
		{
			final String message = plugin.exportGroupToClipboard(name);
			if (message != null)
			{
				JOptionPane.showMessageDialog(this, message, "Export hide group",
					JOptionPane.INFORMATION_MESSAGE);
			}
		}));

		controls.add(iconLabel(DELETE, DELETE_HOVER,
			isDefault ? "Clear group (the Default group can't be deleted)" : "Delete group", () ->
		{
			final int confirm = JOptionPane.showConfirmDialog(this,
				isDefault
					? "Clear all " + count + " objects from \"" + name + "\"?"
					: "Delete group \"" + name + "\" and unhide its " + count + " objects?",
				(isDefault ? "Clear" : "Delete") + " group", JOptionPane.OK_CANCEL_OPTION);
			if (confirm == JOptionPane.OK_OPTION)
			{
				plugin.deleteGroup(name);
			}
		}));

		header.add(nameLabel, BorderLayout.NORTH);
		header.add(controls, BorderLayout.CENTER);
		return header;
	}

	// --- group body --------------------------------------------------------------------

	private List<JPanel> buildGroupRows(HideGroup group, EntryFilter filter)
	{
		final List<JPanel> rows = new ArrayList<>();
		final String groupName = group.getName();

		final List<HideEntry> entries = new ArrayList<>(group.getEntries());
		entries.sort(ENTRY_ORDER);

		if (labelCache.size() > 4096)
		{
			labelCache.clear();
		}
		for (final HideEntry entry : entries)
		{
			// The location label doubles as the area: filter value, so searching
			// matches exactly what the row displays
			final String detail = labelCache.computeIfAbsent(entry, LocationLabel::describe);
			if (!filter.isEmpty() && !filter.matches(entry, groupName, detail))
			{
				continue;
			}
			rows.add(buildRow(entry.getObjectName(), detail, entry.isInstance(),
				() -> plugin.removeEntry(groupName, entry),
				target -> plugin.moveEntry(groupName, target, entry)));
		}

		if (rows.isEmpty() && filter.isEmpty())
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

	// --- drag and drop -------------------------------------------------------------------

	private static final class DragPayload
	{
		private final Consumer<String> moveToGroup;

		private DragPayload(Consumer<String> moveToGroup)
		{
			this.moveToGroup = moveToGroup;
		}
	}

	private static final class PayloadTransferable implements Transferable
	{
		private final DragPayload payload;

		private PayloadTransferable(DragPayload payload)
		{
			this.payload = payload;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors()
		{
			return new DataFlavor[]{DRAG_FLAVOR};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor)
		{
			return DRAG_FLAVOR.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
		{
			if (!DRAG_FLAVOR.equals(flavor))
			{
				throw new UnsupportedFlavorException(flavor);
			}
			return payload;
		}
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

	// --- widgets -------------------------------------------------------------------------

	private JPanel buildRow(String name, String detail, boolean instance, Runnable onRemove, Consumer<String> moveToGroup)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBorder(new EmptyBorder(4, 8, 4, 8));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setToolTipText("Drag onto a group header to move");

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		final JLabel nameLabel = new JLabel(name);
		nameLabel.putClientProperty("html.disable", Boolean.TRUE);
		nameLabel.setForeground(Color.WHITE);

		final JPanel nameLine = new JPanel();
		nameLine.setLayout(new BoxLayout(nameLine, BoxLayout.X_AXIS));
		nameLine.setOpaque(false);
		nameLine.setAlignmentX(LEFT_ALIGNMENT);
		nameLine.add(nameLabel);
		if (instance)
		{
			nameLine.add(Box.createRigidArea(new Dimension(6, 0)));
			nameLine.add(instanceBadge());
		}
		text.add(nameLine);

		if (detail != null)
		{
			final JLabel detailLabel = new JLabel(detail);
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			detailLabel.setAlignmentX(LEFT_ALIGNMENT);
			text.add(detailLabel);
		}

		row.add(text, BorderLayout.CENTER);
		row.add(iconLabel(DELETE, DELETE_HOVER, "Unhide", onRemove), BorderLayout.EAST);

		// Native Swing DnD: the platform runs the drag loop, so delivery is
		// reliable regardless of which child component is under the press.
		final DragPayload payload = new DragPayload(moveToGroup);
		row.setTransferHandler(new TransferHandler()
		{
			@Override
			public int getSourceActions(JComponent c)
			{
				return MOVE;
			}

			@Override
			protected Transferable createTransferable(JComponent c)
			{
				return new PayloadTransferable(payload);
			}

			@Override
			protected void exportDone(JComponent source, Transferable data, int action)
			{
				highlightHeader(null);
			}
		});
		row.addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent e)
			{
				row.getTransferHandler().exportAsDrag(row, e, TransferHandler.MOVE);
			}
		});
		return row;
	}

	private static JLabel instanceBadge()
	{
		final JLabel label = new JLabel("Instance");
		label.setOpaque(true);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.BLACK);
		label.setBackground(ColorScheme.BRAND_ORANGE);
		label.setBorder(new EmptyBorder(1, 4, 1, 4));
		label.setToolTipText("Made inside an instance — applies only in instanced areas using this map template");
		return label;
	}

	private static JLabel iconLabel(ImageIcon icon, ImageIcon hoverIcon, String tooltip, Runnable onClick)
	{
		final JLabel label = new JLabel(icon);
		// Centered so GridLayout cells space the header controls evenly
		label.setHorizontalAlignment(JLabel.CENTER);
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

	private void promptRename(String oldName)
	{
		final String newName = (String) JOptionPane.showInputDialog(this, "Rename group:",
			"Rename group", JOptionPane.PLAIN_MESSAGE, null, null, oldName);
		if (newName == null || newName.trim().isEmpty() || newName.trim().equals(oldName))
		{
			return;
		}
		// Carry the collapse state over to whatever name the plugin settled on —
		// it may sanitize, truncate, or uniquify the requested one
		final String finalName = plugin.renameGroup(oldName, newName);
		if (finalName != null)
		{
			final Boolean expanded = expandedState.remove(oldName);
			if (expanded != null)
			{
				expandedState.put(finalName, expanded);
			}
		}
	}
}
