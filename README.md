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
  server-id: ""           # this server's name when sharing a MySQL database; empty generates one
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

All player-facing chat, including `/wardrobe` command output and the `/wardrobe list` lines. Every key
is optional — delete a line to use the wording shipped in the plugin jar, or set it to `""` to silence
that message. Keep `save-failed` audible: it means what's on screen isn't what's
stored, and continuing to move gear can lose it.

### `gui/wardrobe.yml`

Item, name and lore for each menu element, the sounds block, and the bottom navigation. A key missing
from your copy uses the one shipped in the plugin jar; set a `lore:` to `[]` to show no lore.

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

### Several servers, one wardrobe

Point every server at the same MySQL/MariaDB database and a player's wardrobe follows them from server
to server.

- **The servers must share player inventories too.** The set a player is wearing lives on the player,
  not in the wardrobe table. Without an inventory sync, a player who wears a set on one server arrives
  on the next with that slot marked worn and no armor on them.
- **A wardrobe opens only after the last server saved it.** The server a player left marks their
  wardrobe saved once its last write commits. The next server waits up to five seconds for that before
  opening it; if it is still not saved, the player sees `load-busy` and can try again. A server that
  crashed stops counting after a minute, and its players' wardrobes open elsewhere.
- **No server writes over another's newer copy.** Every wardrobe has a version that each save raises.
  A save only lands if the version is still the one this server loaded, so a stale copy can't undo a
  change made elsewhere. The refused change is logged in full (severe level) and the player sees
  `changed-elsewhere`; opening the wardrobe again shows the current sets.
- **Servers starting together upgrade the database once.** The schema has a version, and the first
  server to start migrates it while the others wait.
- **Give each server its own `server-id`**, or leave it empty and each server keeps a generated one in
  `server-id.txt`. Copying that file to another server makes them look like one server.

## Metrics

Reports anonymous usage to [bStats](https://bstats.org/plugin/bukkit/RoyalWardrobe/32731). Turn it
off for the whole server in `plugins/bStats/config.yml`.
