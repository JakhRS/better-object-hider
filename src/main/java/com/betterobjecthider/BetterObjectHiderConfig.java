/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(BetterObjectHiderPlugin.CONFIG_GROUP)
public interface BetterObjectHiderConfig extends Config
{
	@ConfigItem(
		position = 0,
		keyName = "requireShift",
		name = "Require Shift for menu options",
		description = "Only show Hide/Unhide options while holding Shift, keeping normal right-click menus clean"
	)
	default boolean requireShift()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "revealAll",
		name = "Reveal hidden objects",
		description = "Temporarily show all hidden objects so you can right-click them to unhide"
	)
	default boolean revealAll()
	{
		return false;
	}

	@ConfigItem(
		position = 2,
		keyName = "showHelp",
		name = "Show usage help",
		description = "Show the short how-to box at the top of the side panel"
	)
	default boolean showHelp()
	{
		return true;
	}

	@Range(max = 10080)
	@Units(Units.MINUTES)
	@ConfigItem(
		position = 3,
		keyName = "activeGroupTimeoutMinutes",
		name = "Active group timeout",
		description = "If the active group hasn't been used for this long, new hides go back to the Default group "
			+ "(prevents forgotten selections from collecting stray hides). The timer refreshes every time you "
			+ "hide something or pick an active group. 0 = never reset."
	)
	default int activeGroupTimeoutMinutes()
	{
		return 60;
	}

	// Name-based schema (v3). The old "groups" key held an ID-based format and is
	// intentionally not read — a fresh install/schema, nothing shipped on the old one.
	@ConfigItem(
		keyName = "groupsV2",
		name = "",
		description = "",
		hidden = true
	)
	default String groups()
	{
		return "";
	}

	@ConfigItem(
		keyName = "activeGroup",
		name = "",
		description = "",
		hidden = true
	)
	default String activeGroup()
	{
		return "";
	}
}
