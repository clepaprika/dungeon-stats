# Dungeon Stats (Fabric, Minecraft 26.3)

Client-side mod that tracks, **per dungeon level**:
- chests looted
- Dragon Eggs, Embers and Star Remnants taken from those chests

It only runs on your game, so it works on any server.

## Build it

Requirements: **JDK 25** (e.g. Temurin 25). IntelliJ IDEA 2025.3+ if you use an IDE.

```
./gradlew build          (Windows: gradlew.bat build)
```

The mod jar ends up in `build/libs/dungeonstats-1.0.0.jar`. Copy it into your `.minecraft/mods` folder next to Fabric API.

To test in a dev client instead: `./gradlew runClient`.

## How it works

The mod reads the server's chat messages. You don't have to do anything:

| Server message | What the mod does |
|---|---|
| `You have entered Prisma Dungeon #16` | Switches to level 16 ("Prisma Dungeon #16") and starts a new run |
| `Added Rune Key, Ember, Golden Carrot to the loot pool.` | +1 chest looted (+2 for a double chest), +1 of each item |
| `Collected 3 x Dragon Eggs, 11 x Embers, Star Remnant, ... from the loot pool.` | +1 end reward claimed, items go into the separate **End rewards** section |

Items in your categories (Dragon Eggs, Embers, Star Remnants) appear on the HUD. Every item gets recorded, so `/dstats items` lists all of them.
Anything after the item list, such as `(~12 deggs)`, is ignored. Messages that players type in chat are ignored.

Everything is split into **Chests** (normal chests) and **End rewards** (the reward chests you claim at the end).

The mod keeps three sets of stats:
- **Last dungeon:** the run you're in now, or the last one you did. This is what the HUD shows. A new run starts each time you enter a dungeon.
- **All dungeons:** everything ever, plus how many runs you've done.
- **Per dungeon:** each dungeon number, summed over all your runs of it.

**Double chests:** when you right-click a chest, the mod notes whether it's a double chest. The next loot message then counts as 2 chests. If you didn't right-click it (no click in the last 30 seconds), it checks the chest under your crosshair instead.

## Controls

| Key (default) | Action |
|---|---|
| J | Show / hide the HUD |
| Page Up / Page Down | Next / previous dungeon level |
| K | Pause / resume tracking |

Rebind them in Options → Controls → Key Binds → Dungeon Stats.

## Commands

| Command | What it does |
|---|---|
| `/dstats` | Last dungeon + all-time total |
| `/dstats <number>` | One dungeon, e.g. `/dstats 26`: how many chests it has (most looted in one run), last run, average, recent runs, and its totals |
| `/dstats levels` | Each dungeon number, summed over all your runs of it |
| `/dstats level <n>` | Set the current level |
| `/dstats items` | Every item from your last dungeon (chests and end rewards listed separately) |
| `/dstats items all` | Every item ever |
| `/dstats items <level>` | Every item from one dungeon number, all runs |
| `/dstats pause` / `resume` | Stop / restart tracking |
| `/dstats hud` | Show / hide the HUD |
| `/dstats hud scale <size>` | HUD size: `1` = normal, `0.5` = half, `2` = double (0.25 to 4) |
| `/dstats hud position <x> <y>` | Distance in pixels from the HUD's corner |
| `/dstats hud corner <corner>` | Stick the HUD to `top_left`, `top_right`, `bottom_left` or `bottom_right` |
| `/dstats hud reset` | Back to the default size and position |
| `/dstats reset` | Clear everything (last dungeon, all-time total, per dungeon) |
| `/dstats reset <level>` | Clear one dungeon number (all-time total unchanged) |
| `/dstats reload` | Reload the config file |

## Config

`config/dungeonstats/config.json` (created on first launch):

```json
{
  "categories": [
    { "name": "Dragon Eggs",   "match": ["dragon egg"] },
    { "name": "Embers",        "match": ["ember"] },
    { "name": "Star Remnants", "match": ["star remnant"] }
  ],
  "autoDetectLevel": true,
  "enterDungeonPattern": "^You have entered (.+?) #(\\d+)",
  "lootPoolPattern": "^Added (.+?) to the loot pool",
  "rewardPattern": "^Collected (.+?) from the loot pool",
  "hudCorner": "top_left",
  "hudX": 4,
  "hudY": 4,
  "hudScale": 1.0
}
```

Add more words to `match` or add new categories, e.g. `{ "name": "Rune Keys", "match": ["rune key"] }`. If the server ever changes its messages, adjust the three patterns. Then run `/dstats reload`.
Your stats are saved in `config/dungeonstats/stats.json`.
