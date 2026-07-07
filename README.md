# Better Object Hider

Hide game objects individually (per tile) or by ID, organized into groups you can toggle and share. A successor to the classic Object Hider.

## Background

After the Blood Moon Rises update I wanted to hide a few specific trees cluttering one spot in Vampyrium. Existing hiders work per object ID, so hiding those trees removed every identical tree across the whole region. This plugin adds per-tile hiding — hide that tree, keep the rest — plus the group management that a longer hide list ends up needing.

Credit to [custom-object-hider](https://github.com/LuxOG/custom-object-hider) by LuxOG (BSD-2-Clause), whose rendering approach this plugin builds on.

## Comparison

| | Classic Object Hider | Better Object Hider |
|---|---|---|
| Hide every object with an ID | ✔ | ✔ |
| Hide an ID only within the local area | — | ✔ |
| Hide one specific object on one tile | — | ✔ |
| Object types | All four | All four (game, wall, decoration, ground) |
| Works in instances (POH, raids) | ID-hides only | ID- and tile-hides survive instance regeneration |
| Organize hides | Single flat list | Named groups with enable/disable |
| Share hides | — | Export/import via clipboard code |
| Manage hides | Config text field | Side panel: names, coordinates, drag-and-drop |
| Undo a hide | Reveal toggle | Reveal toggle or panel |

Purely cosmetic and client-side: rendering suppression only, nothing sent to the server, nothing automated, no network calls. Hides persist between sessions.

## Usage

### Hiding

1. Hold **Shift** and right-click a game object (Shift requirement can be disabled in config).
2. Choose the scope:
   - **Hide this one** — that object, that tile.
   - **Hide all of ID in area** — every object of that type within the local map area (a 64×64 map square, the same unit Ground Markers uses). Works in instances: the area follows the instance template, so it survives regeneration.
   - **Hide all of ID** — every object of that type, everywhere.

The object disappears immediately; no scene reload.

### The side panel

- **New group** creates a group. The orange group is *active* — new hides land there; click the circle icon on another group to switch.
- The eye icon enables/disables a whole group's hides at once.
- Click a group's name to collapse/expand it.
- Repeated hides of the same object type stack as `Name ×N`; the stack's ✕ removes all of them, or expand to remove one at a time.
- Drag an entry onto another group's header to move it there.

### Unhiding

- Panel: click the ✕ next to an entry.
- In the world: enable **Reveal hidden objects** in config, Shift+right-click the object, pick the Unhide option, then disable the toggle.

### Sharing groups

- Export: click the export icon on a group header — the group is copied to your clipboard as a text code.
- Import: copy someone's code, click **Import** in the panel.

Imported codes are sanitized; malformed entries and disallowed objects are stripped.

## Limitations

- Hides are scoped to where they were made: hides created inside an instance apply only in instances (including every occurrence of that template chunk in a raid layout), and hides created in the overworld never affect instances.
- A small set of raid-mechanic objects (e.g. the ToB Bloat pillar and Sotetseg maze tiles) cannot be hidden, and are stripped from imports, per plugin hub rules.
- No pre-made hide lists ship with the plugin; sharing is user-to-user only.

## Support

Donations are appreciated but never required — every feature is and will remain free.

[ko-fi.com/jakhrs](https://ko-fi.com/jakhrs)
