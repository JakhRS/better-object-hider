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
 */
@Data
public class HideGroup
{
	private String name;
	private boolean enabled = true;
	// Object IDs hidden everywhere
	private Set<Integer> ids = new HashSet<>();
	// Specific objects by tile, "id:regionId:regionX:regionY:plane"
	private Set<String> tiles = new HashSet<>();
	// Object IDs hidden within one map region (64x64), "id:regionId".
	// Absent in pre-area exports; Gson leaves the initializer, so old codes import fine.
	private Set<String> areas = new HashSet<>();
}
