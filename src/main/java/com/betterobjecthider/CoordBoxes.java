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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared loader for the coordinate-box TSV tables ({@code places.tsv},
 * {@code provinces.tsv}): {@code minX<TAB>minY<TAB>maxX<TAB>maxY<TAB>name},
 * {@code #} comments and blank lines skipped, malformed lines tolerated.
 * Lookup is a first-match-wins point-in-rectangle scan, so author files
 * most-specific first. All data is static, factual OSRS geography.
 */
@Slf4j
final class CoordBoxes
{
	static final class Box
	{
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final String name;

		Box(int minX, int minY, int maxX, int maxY, String name)
		{
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.name = name;
		}
	}

	private CoordBoxes()
	{
	}

	/** @return the parsed box, or null when the line is a comment, blank, or malformed */
	static Box parseLine(String line)
	{
		if (line == null || line.isEmpty() || line.charAt(0) == '#')
		{
			return null;
		}
		final String[] fields = line.split("\t");
		if (fields.length < 5)
		{
			return null;
		}
		try
		{
			final int minX = Integer.parseInt(fields[0].trim());
			final int minY = Integer.parseInt(fields[1].trim());
			final int maxX = Integer.parseInt(fields[2].trim());
			final int maxY = Integer.parseInt(fields[3].trim());
			final String name = fields[4].trim();
			if (minX > maxX || minY > maxY || name.isEmpty())
			{
				return null;
			}
			return new Box(minX, minY, maxX, maxY, name);
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	static List<Box> load(String resource)
	{
		final List<Box> boxes = new ArrayList<>();
		try (InputStream in = CoordBoxes.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.warn("{} missing", resource);
				return Collections.emptyList();
			}
			final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				final Box box = parseLine(line);
				if (box != null)
				{
					boxes.add(box);
				}
			}
		}
		catch (IOException ex)
		{
			log.warn("failed to load {}", resource, ex);
		}
		return boxes;
	}

	/** @return the name of the first box containing the point (bounds inclusive), or null */
	static String lookup(List<Box> boxes, int x, int y)
	{
		for (final Box box : boxes)
		{
			if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY)
			{
				return box.name;
			}
		}
		return null;
	}
}
