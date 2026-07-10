/*
 * Copyright (c) 2026, Jack Hartnett
 * All rights reserved.
 *
 * BSD-2-Clause. See LICENSE.
 */
package com.betterobjecthider;

import java.util.HashSet;
import java.util.Set;
import lombok.Data;

/**
 * A named collection of hidden objects. Serialized to config (and to the
 * clipboard for sharing) via default Gson field mapping — keep it a plain POJO.
 * Every hide is a {@link HideEntry} keyed by object name + location; the plugin
 * stores no object IDs.
 */
@Data
public class HideGroup
{
	// Export/config schema version. Older clients ignore it (Gson skips unknown
	// JSON fields); bump only when the shape changes so future readers can migrate.
	private int v = 1;
	private String name;
	private boolean enabled = true;
	private Set<HideEntry> entries = new HashSet<>();
}
