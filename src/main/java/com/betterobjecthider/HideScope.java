/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

/**
 * How far a hide reaches. Every scope is by object <em>name</em> — none targets
 * an object ID.
 *
 * <ul>
 *   <li>{@code TILE} — objects of the name on one specific tile.</li>
 *   <li>{@code AREA} — objects of the name anywhere in one 64x64 map region.</li>
 *   <li>{@code GLOBAL} — objects of the name anywhere (e.g. "hide everything
 *       named Ice chunks"). Still name-based and still named-only.</li>
 * </ul>
 */
public enum HideScope
{
	TILE,
	AREA,
	GLOBAL
}
