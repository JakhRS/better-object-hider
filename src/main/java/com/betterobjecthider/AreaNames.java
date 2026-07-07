/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Map region ID → human-readable area name, loaded once from a bundled resource.
 *
 * <p>The data is derived from RuneLite's {@code DiscordGameEventType}
 * (BSD-2-Clause) — factual OSRS location data. It covers notable areas (cities,
 * minigames, bosses, dungeons, ...) but not generic overworld regions; callers
 * fall back to coordinates when {@link #get(int)} returns null.
 */
@Slf4j
public final class AreaNames
{
	private static final Map<Integer, String> NAMES = load();

	private AreaNames()
	{
	}

	/** @return the friendly area name for a map region, or null if unknown */
	public static String get(int regionId)
	{
		return NAMES.get(regionId);
	}

	private static Map<Integer, String> load()
	{
		final Map<Integer, String> map = new HashMap<>();
		try (InputStream in = AreaNames.class.getResourceAsStream("area_names.tsv"))
		{
			if (in == null)
			{
				log.warn("area_names.tsv missing");
				return Collections.emptyMap();
			}
			final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.charAt(0) == '#')
				{
					continue;
				}
				final int tab = line.indexOf('\t');
				if (tab <= 0)
				{
					continue;
				}
				try
				{
					map.put(Integer.parseInt(line.substring(0, tab).trim()), line.substring(tab + 1).trim());
				}
				catch (NumberFormatException ignored)
				{
					// skip malformed line
				}
			}
		}
		catch (IOException ex)
		{
			log.warn("failed to load area names", ex);
		}
		return map;
	}
}
