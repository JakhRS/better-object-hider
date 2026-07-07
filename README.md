# Better Object Hider

Declutter your view by hiding the specific scenery objects you don't want to see — the one gnarled tree in a corner, a fence, a decoration — without touching identical objects elsewhere. Organize your hides into groups you can toggle and share.

## How it works

You **shift+right-click a named object you can see** and choose to hide it. That's the whole interaction — there are no ID lists and nothing to type.

- A hide is stored by the object's **name plus a map location** (a specific tile, or the local area), the same way Ground Markers and Object Indicators store a spot. It is not tied to an object ID.
- **Objects with no name can't be hidden.** If the game doesn't give an object a name, no hide option appears for it.
- Hiding "Tree here" hides every object *named* "Tree" at that spot — it never singles out one variant of a shared name.

Everything is purely cosmetic and client-side: rendering suppression only. Nothing is sent to the server, nothing is automated, there are no network calls, and no object IDs are entered, stored, or targeted.

**By design:**

- **Selection only** — you can only hide an object you've pointed at in the world; there is no way to type or paste an ID.
- **Named objects only** — objects the game leaves nameless cannot be hidden at all.
- **By name, never by ID** — hiding "Tree" hides every object *named* Tree in the scope you pick; it never targets one ID variant of a shared name.

## Usage

### Hiding

1. Hold **Shift** and right-click a named scenery object (the Shift requirement can be turned off in config).
2. Choose the reach:
   - **Hide this** — that object's name, on that one tile.
   - **Hide all in this area** — that object's name, anywhere in the local map region.
   - **Hide all everywhere** — every object of that name, wherever it appears.

The object disappears immediately; no scene reload. (Objects the game leaves unnamed won't offer a hide option.)

### The side panel

Open the **Better Object Hider** icon in the sidebar to manage everything:

- **New group** creates a group. The orange group is *active* — new hides land there; click the circle icon on another group to switch.
- The eye icon enables or disables a whole group's hides at once.
- Click a group's name to collapse or expand it.
- Each row shows the object's name and where it's hidden — the readable area name where one exists (e.g. *Tzhaar Fight Caves*), otherwise the tile coordinates. The red ✕ unhides it.
- Rows made inside an instance carry an **Instance** badge.
- Drag any row onto another group's header to move it there.
- Rename groups with the pencil icon; the export icon copies a group to your clipboard.

### Unhiding

- Panel: click the red ✕ next to a row.
- In the world: enable **Reveal hidden objects** in config, shift+right-click the object, pick the Unhide option, then turn the toggle back off.

### Sharing groups

- **Export**: click the export icon on a group header — the group is copied to your clipboard as a text code.
- **Import**: copy someone's code, then click **Import** in the panel.

Codes contain object names and locations, and are sanitized on import (malformed or disallowed entries are dropped).

## Limitations

- Objects whose appearance changes with game state (morphing/multiloc objects) are matched on their base name and may not hide reliably.
- Hides made inside an instance are tagged as such and apply only in instanced areas that use the same map template; they never affect the overworld.
- A small set of raid-mechanic objects can't be hidden and are stripped from imports, per plugin hub rules.

## Support

Donations are appreciated but never required — every feature is and will remain free.

[ko-fi.com/jakhrs](https://ko-fi.com/jakhrs)
