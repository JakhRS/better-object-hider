/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A parsed side-panel search query. Terms are whitespace-separated and ALL must
 * match (AND). Matching is case-insensitive; a term containing {@code *} is a
 * wildcard pattern tested against the whole value and against each of its
 * words, otherwise it is a substring test.
 *
 * <ul>
 *   <li>plain term — matches the object name (e.g. {@code tree}, {@code bank*}).</li>
 *   <li>{@code area:x} — matches the hide's place/area label (e.g.
 *       {@code area:kandarin}). Global hides apply everywhere, so they match
 *       any {@code area:} term.</li>
 *   <li>{@code group:x} — matches the containing group's name.</li>
 *   <li>{@code scope:tile}, {@code scope:area}, {@code scope:everywhere} —
 *       matches the hide's reach ({@code scope:global} is an alias).</li>
 *   <li>{@code is:instance} — only hides made inside an instance.</li>
 * </ul>
 *
 * Unknown prefixes are treated as part of a plain name term. Pure logic, no
 * client types — unit-tested in {@code BetterObjectHiderLogicTest}.
 */
public final class EntryFilter
{
	private final List<Term> terms;

	private EntryFilter(List<Term> terms)
	{
		this.terms = terms;
	}

	public static EntryFilter parse(String query)
	{
		final List<Term> terms = new ArrayList<>();
		if (query != null)
		{
			for (final String raw : query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
			{
				final Term term = parseTerm(raw);
				if (term != null)
				{
					terms.add(term);
				}
			}
		}
		return new EntryFilter(terms);
	}

	/** True when the query had no usable terms — everything matches. */
	public boolean isEmpty()
	{
		return terms.isEmpty();
	}

	/**
	 * @param areaName the entry's place/area label (what the row displays), or
	 *                 null when only coordinates are known
	 */
	public boolean matches(HideEntry entry, String groupName, String areaName)
	{
		for (final Term term : terms)
		{
			if (!term.test(entry, groupName, areaName))
			{
				return false;
			}
		}
		return true;
	}

	private interface Term
	{
		boolean test(HideEntry entry, String groupName, String areaName);
	}

	private static Term parseTerm(String raw)
	{
		if (raw.isEmpty())
		{
			return null;
		}
		final int colon = raw.indexOf(':');
		if (colon > 0 && colon < raw.length() - 1)
		{
			final String value = raw.substring(colon + 1);
			switch (raw.substring(0, colon))
			{
				case "area":
					// GLOBAL hides apply everywhere, so any area term includes them
					return (e, g, area) -> e.getScope() == HideScope.GLOBAL || matchesValue(value, area);
				case "group":
					return (e, g, area) -> matchesValue(value, g);
				case "scope":
					final HideScope scope = scopeFor(value);
					return scope == null ? null : (e, g, area) -> e.getScope() == scope;
				case "is":
					return "instance".equals(value) ? (e, g, area) -> e.isInstance() : null;
				default:
					break; // unknown prefix: fall through to a plain name term
			}
		}
		return (e, g, area) -> matchesValue(raw, e.getObjectName());
	}

	private static HideScope scopeFor(String value)
	{
		switch (value)
		{
			case "tile":
				return HideScope.TILE;
			case "area":
				return HideScope.AREA;
			case "everywhere":
			case "global":
				return HideScope.GLOBAL;
			default:
				return null;
		}
	}

	/**
	 * Case-insensitive match of an already-lower-cased pattern against a value:
	 * substring by default. A pattern containing {@code *} is a wildcard tested
	 * against the whole value and against each word of it, so {@code tree*}
	 * finds "Tree", "Trees" and "Darkwood Tree" alike.
	 */
	static boolean matchesValue(String pattern, String value)
	{
		if (value == null)
		{
			return false;
		}
		final String lower = value.toLowerCase(Locale.ROOT);
		if (pattern.indexOf('*') < 0)
		{
			return lower.contains(pattern);
		}
		final String regex = wildcardRegex(pattern);
		if (lower.matches(regex))
		{
			return true;
		}
		for (final String word : lower.split("\\s+"))
		{
			if (word.matches(regex))
			{
				return true;
			}
		}
		return false;
	}

	private static String wildcardRegex(String pattern)
	{
		final StringBuilder regex = new StringBuilder();
		int start = 0;
		for (int i = pattern.indexOf('*'); i >= 0; i = pattern.indexOf('*', start))
		{
			if (i > start)
			{
				regex.append(Pattern.quote(pattern.substring(start, i)));
			}
			regex.append(".*");
			start = i + 1;
		}
		if (start < pattern.length())
		{
			regex.append(Pattern.quote(pattern.substring(start)));
		}
		return regex.toString();
	}
}
