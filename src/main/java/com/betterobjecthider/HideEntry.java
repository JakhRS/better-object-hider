/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import lombok.Data;

/**
 * A single hidden object, identified by its in-game NAME plus a map location.
 * The plugin never stores or acts on object IDs — a hide targets "the object
 * named X at this spot," and hiding is refused entirely for objects that have
 * no name.
 *
 * <p>{@link #scope} controls reach (tile / area / global). Location is
 * region-relative (instance-stable, like Ground Markers): TILE uses all of
 * regionId/X/Y/plane, AREA uses regionId, GLOBAL uses none. {@code instance}
 * records whether a TILE/AREA hide was made inside an instanced area, so it
 * never leaks to the template's overworld location (ignored for GLOBAL).
 *
 * <p>Plain POJO: serialized to config and to the clipboard (for sharing) via
 * default Gson field mapping. {@code @Data} provides equals/hashCode so a
 * {@code Set<HideEntry>} de-duplicates naturally.
 */
@Data
public class HideEntry
{
	private String objectName;
	private HideScope scope;
	private int regionId;
	private int regionX;
	private int regionY;
	private int plane;
	private boolean instance;
}
