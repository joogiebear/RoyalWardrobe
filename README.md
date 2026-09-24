# RoyalWardrobe

A Hypixel-style armour wardrobe. Players store complete gear sets and swap between them from a menu,
instead of juggling armour in their inventory.

Part of the Royal plugin suite. Works standalone; scopes wardrobes per profile when RoyalSkyblock is
installed. Requires Paper 26.2 or newer.

---

## How it works

The menu is a grid of **columns**, one per saved set, read top to bottom: helmet, chestplate,
leggings, boots. Below each column is a dye that acts as its button.

| Dye | State | Click |
|---|---|---|
| Gray | Empty slot | Stores the armour you're currently wearing |
| Pink | Holds a set | Equips it, putting whatever you were wearing back into the slot |
| Lime | The set you're wearing | Unequips it back into its slot |
| Red pane | Slot beyond your permission limit | Nothing — but gear already inside stays visible and retrievable |
| Structure void | A stored set that couldn't be read back | Nothing — it is left untouched for an admin to recover (the console logs which) |

Right-click a set's dye to name it. Names are plain text; colour codes are stripped.

The set you are wearing is locked in place: it can't be edited while active, so a swap can't lose
half an outfit. Gear is always **moved**, never copied — the active set lives on the player, and its
saved row is empty until it comes off.

You can also build sets by hand: drag individual pieces into any inactive column, or shift-click a
piece from your inventory to drop it into the first empty matching slot. A piece goes in the row it is
worn in, so anything the game lets you wear there counts, custom wearables included.

If the wardrobe can't be read (the database is down, say), it stays shut rather than opening empty:
storing into a slot that only looks free would overwrite the set that is really there.

---

## Commands

```text
/wardrobe            Open your wardrobe        (aliases: /wr, /gear)
/wardrobe equip <n>  Swap to set n without opening the menu (keybind- and macro-friendly)
/wardrobe list       List your sets
/wardrobe reload     Reload config, messages, menu and scope; closes open wardrobes first.
                     Storage settings apply on restart.
```

## Permissions

```text
royalwardrobe.use     default: true    Open and use the wardrobe
royalwardrobe.admin   default: op      /wardrobe reload
```

### Slot limits

Players get `slots.default` slots (1 out of the box). Grant more with numbered permissions — the
highest one a player holds wins:

```text
royalwardrobe.slots.3     three slots
royalwardrobe.slots.10    ten slots
royalwardrobe.slots.*     the configured maximum
```

The node prefix is `slots.permission`, so it can be renamed. `slots.max` caps everything, and no rank
can exceed the menu's own capacity.

---

## Configuration

### `config.yml`

```yaml
storage:
  type: SQLITE            # or MYSQL
  sqlite-file: wardrobe.db
  mysql:
    host: localhost
    port: 3306
    database: royalwardrobe
    username: root
    password: ""
    properties: useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true
    pool-size: 10

slots:
  default: 1                            # slots with no rank
  permission: royalwardrobe.slots       # prefix for the numbered nodes above
  max: 18                               # hard ceiling

wardrobe:
  # Scopes a wardrobe per RoyalSkyblock profile. Blank it for one wardrobe per player.
  scope-placeholder: "%royalskyblock_profile_id%"
```

Wardrobes are per profile whenever the placeholder's PlaceholderAPI expansion is registered. In that
mode a player with no active profile can't open their wardrobe; it deliberately does not fall back to
a per-player one, which every profile would share, making it a way to carry gear into or out of an
Ironman profile.

Switching storage backend does **not** migrate existing wardrobes.

### `messages.yml`

All player-facing chat. Every key is optional — delete a line to use its built-in wording, or set it
to `""` to silence that message. Keep `save-failed` audible: it means what's on screen isn't what's
stored, and continuing to move gear can lose it.

### `gui/wardrobe.yml`

Item, name and lore for each menu element, the sounds block, and the bottom navigation.

Navigation buttons are placed by `row`/`column` (both 1-indexed), and a raw `slot:` index still works.
Each may carry a `left-click:` list that **replaces** its built-in behaviour, and the `buttons:` list
adds your own:

```yaml
buttons:
  - item: 'ender_pearl name:"&bSpawn"'
    row: 6
    column: 3
    left-click:
      - id: player_command
        args: {command: "spawn"}
      - id: close
```

Available click ids: `close` (or `close_inventory`), `message`, `player_command`, `console_command`,
`play_sound`.

The gear grid itself is computed from `columns-per-page` and `pages` rather than authored slot by
slot, because a column *is* one outfit read helmet to boots — those positions carry meaning.

---

## Storage

One row per wardrobe slot. Writes go through a single writer thread so two rapid clicks on the same
slot can't land out of order, and a failed write tells the player rather than failing silently. On
shutdown the queue is drained before the pool closes.

## Metrics

Reports anonymous usage to [bStats](https://bstats.org/plugin/bukkit/RoyalWardrobe/32731). Turn it
off for the whole server in `plugins/bStats/config.yml`.
