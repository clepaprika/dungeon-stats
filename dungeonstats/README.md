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

**A run is only saved once you collect the end loot** (the first "Collected ... from the loot pool" message). Until then it's kept in memory only. If you lose all your lives ("<you> lost all of their lives!"), close the game or enter another dungeon before the end loot, that run is thrown away. The same message about a teammate is ignored. Re-entering the *same* dungeon before finishing (e.g. after rejoining) continues the run instead.

**Time** (HUD only): starts when you enter a dungeon. When the server says "Dungeon completed in 724.6 seconds!", it stops and shows "Completed in 724.6 seconds (12:04)". It also stops if you lose all your lives. It keeps running if you briefly leave and return to the same run. Never saved.

**Mobs killed** (HUD only): your kills during the current run, melee and ranged; teammates' kills don't count. It uses Minecraft's "Mobs Killed" statistic, which the mod asks the server for every 3 seconds, so the number can lag a few seconds behind. It resets when you enter a new dungeon and is never saved.

**Leaderboards:** open the server's Fastest Times menu and the mod saves the leaderboard shown (player names and times) under that dungeon's number. Click through to other dungeons in the menu to save theirs too. `/dstats <number>` shows it in a "Leaderboard" section, with your own name in green. It updates whenever you open the menu again.

**Time vs leaderboard:** when the server says "<you> completed Prisma Dungeon #24 in 724.6 seconds!", the mod compares that time with the saved leaderboard for #24 and says in chat whether you beat #1 (and by how much), which time you beat and how far you are from #1, or how far you are from #5. If you're already on the leaderboard, it also says whether this was a personal best. Load the leaderboard first by opening the Fastest Times menu.

The mod keeps these stats:
- **In progress:** the run you're in now. The HUD shows it live; it isn't saved yet.
- **Last dungeon:** the last finished run.
- **All dungeons:** every finished run, plus how many runs you've done.
- **Per dungeon:** each dungeon number, summed over all your finished runs of it.

**Double chests:** when you right-click a chest, the mod notes whether it's a double chest. The next loot message then counts as 2 chests. If you didn't right-click it (no click in the last 30 seconds), it checks the chest under your crosshair instead.

## Settings menu

`/dstats settings` opens a settings panel:

| Left | Right |
|---|---|
| Mob counter: ON/OFF | HUD position... (drag the box with the mouse) |
| Timer: ON/OFF | HUD corner (click to cycle) |
| [-] HUD scale [+] (click the middle to reset to 1x) | Show HUD: ON/OFF |
| Reset all data (asks for confirmation) | Tracking: ON/OFF |
| Help | |

A preview of the HUD is drawn while the menu is open, so you can see changes right away.

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
| `/dstats` | Run in progress (if any), last finished dungeon, all-time total |
| `/dstats <number>` | One dungeon, e.g. `/dstats 26`: how many chests it has (most looted in one run), last run, average, recent runs, its totals, and its leaderboard |
| `/dstats levels` | Each dungeon number, summed over all your runs of it |
| `/dstats level <n>` | Set the current level |
| `/dstats items` | Every item from your last dungeon (chests and end rewards listed separately) |
| `/dstats items all` | Every item ever |
| `/dstats items <level>` | Every item from one dungeon number, all runs |
| `/dstats pause` / `resume` | Stop / restart tracking |
| `/dstats help` | List every command with a short description |
| `/dstats settings` | Open the settings menu |
| `/dstats hud` | Show / hide the HUD |
| `/dstats hud scale <size>` | HUD size: `1` = normal, `0.5` = half, `2` = double (0.25 to 4) |
| `/dstats hud position <x> <y>` | Distance in pixels from the HUD's corner |
| `/dstats hud corner <corner>` | Stick the HUD to `top_left`, `top_right`, `bottom_left` or `bottom_right` |
| `/dstats hud reset` | Back to the default size and position |
| `/dstats reset` | Clear everything (last dungeon, all-time total, per dungeon, leaderboards) |
| `/dstats reset <level>` | Clear one dungeon number (all-time total unchanged) |
| `/dstats reload` | Reload the config file |

## Config

`config/dungeonstats/config.json` (created on first launch):

```json
{
  "categories": [
    { "name": "Dragon Eggs",   "match": ["dragon egg"] },
    { "name": "Embers",        "match": ["ember"], "unitName": "ember", "craftCost": 4, "craftInto": "dragon egg" },
    { "name": "Star Remnants", "match": ["star remnant"] }
  ],
  "autoDetectLevel": true,
  "enterDungeonPattern": "^You have entered (.+?) #(\\d+)",
  "lootPoolPattern": "^Added (.+?) to the loot pool",
  "rewardPattern": "^Collected (.+?) from the loot pool",
  "completedPattern": "^Dungeon completed in ([0-9.,]+) seconds",
  "playerCompletedPattern": "^(\\S+) completed (.+?) #(\\d+) in ([0-9.,]+) seconds",
  "livesLostPattern": "^(\\S+) lost all of their lives",
  "hudCorner": "top_left",
  "hudX": 4,
  "hudY": 4,
  "hudScale": 1.0
}
```

Embers are folded into Dragon Eggs with the crafting recipe (4 embers = 1 dragon egg), everywhere stats are shown: 4 eggs + 38 embers display as "Dragon Eggs: 13, Embers: 2". The saved counts are still the real eggs and embers. Change `craftCost` / `craftInto` if the recipe changes, or remove `craftInto` to show plain numbers.

Add more words to `match` or add new categories, e.g. `{ "name": "Rune Keys", "match": ["rune key"] }`. If the server ever changes its messages, adjust the patterns. Then run `/dstats reload`.
Your stats are saved in `config/dungeonstats/stats.json`.
