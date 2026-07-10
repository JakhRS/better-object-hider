/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider.devtools;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.cache.AreaManager;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.WorldMapElementDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;

/**
 * Dev tool, not part of the plugin: regenerates
 * {@code src/main/resources/com/betterobjecthider/map_labels.tsv} from the
 * local OSRS game cache. The world map's text labels (the names Jagex draws on
 * the in-game map, with exact world coordinates) live in the cache as world map
 * elements joined to area definitions; this dumps every named one. Factual game
 * geography — the same data every player sees on the world map.
 *
 * <p>Run with {@code ./gradlew dumpMapLabels} after the dev client has
 * downloaded a cache (default location {@code ~/.runelite/jagexcache}).
 */
public final class MapLabelsDumper
{
	private MapLabelsDumper()
	{
	}

	public static void main(String[] args) throws IOException
	{
		final File cacheDir = new File(args.length > 0
			? args[0]
			: System.getProperty("user.home") + "/.runelite/jagexcache/oldschool/LIVE");
		final Path out = Paths.get("src/main/resources/com/betterobjecthider/map_labels.tsv");

		// key = "x\ty\tz" for de-dupe + stable sort; value = label text
		final Map<String, String> labels = new TreeMap<>();
		int elements = 0;
		try (Store store = new Store(cacheDir))
		{
			store.load();
			final AreaManager areas = new AreaManager(store);
			areas.load();
			final WorldMapManager worldMap = new WorldMapManager(store);
			worldMap.load();

			final Map<String, Integer> statCounts = new TreeMap<>();
			final Map<String, String> statSamples = new TreeMap<>();
			for (WorldMapElementDefinition element : worldMap.getElements())
			{
				elements++;
				final AreaDefinition area = areas.getArea(element.getAreaDefinitionId());
				final String name = cleanLabel(area == null ? null : area.name);
				if (name == null)
				{
					continue;
				}
				final String statKey = "cat=" + area.category + " scale=" + area.textScale
					+ String.format(" color=%08x", area.textColor);
				statCounts.merge(statKey, 1, Integer::sum);
				statSamples.merge(statKey, name, (old, add) ->
					old.length() > 120 || old.contains(add) ? old : old + " | " + add);
				// Keep white locality/town labels (scale 0/1). Yellow scale-1 labels
				// are dungeon level markers ("Upper level"); scale 2 is the ocean/
				// kingdom banner tier, which the plugin's province layer covers.
				if (area.textScale > 1 || (area.textColor & 0xffffff) != 0xffffff)
				{
					continue;
				}
				final Position pos = element.getWorldPosition();
				labels.put(pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ(), name);
			}
			for (Map.Entry<String, Integer> stat : statCounts.entrySet())
			{
				System.out.println(stat.getKey() + "  count=" + stat.getValue()
					+ "  e.g. [" + statSamples.get(stat.getKey()) + "]");
			}
		}

		try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8)))
		{
			writer.println("# x<TAB>y<TAB>plane<TAB>label — OSRS world map text labels, extracted from the");
			writer.println("# game cache (the names Jagex draws on the in-game world map). Factual game");
			writer.println("# geography. Regenerate with: ./gradlew dumpMapLabels");
			for (Map.Entry<String, String> entry : labels.entrySet())
			{
				writer.println(entry.getKey() + "\t" + entry.getValue());
			}
		}
		System.out.println("map elements scanned: " + elements);
		System.out.println("named labels written: " + labels.size() + " -> " + out);
	}

	/** Multi-line labels use {@code <br>}; drop any other markup and blank names. */
	static String cleanLabel(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		final String name = raw
			.replaceAll("(?i)<br>", " ")
			.replaceAll("<[^>]*>", "")
			.replaceAll("\\s+", " ")
			.trim();
		return name.isEmpty() ? null : name;
	}
}
