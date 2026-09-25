<p align="center">
  <img src="src/main/resources/assets/shinyhunter/icon.png" width="96" alt="Shiny Hunter icon">
</p>

<h1 align="center">Shiny Hunter</h1>

<p align="center">
  <b>A Critter Safari companion for Hypixel Skyblock.</b><br>
  Sparkling alerts, a live critter checklist, clearer highlights, honeyhive and Snoozling wall markers, run timers and party tools — all in one client-side Fabric mod.
</p>

<p align="center">
  Minecraft <b>26.1.2</b> · Fabric · Client-side only
</p>

---

## Features

### ✨ Sparkling alerts
- A title card, chat banner and a three-note fanfare the moment a **SPARKLING** critter comes into view.
- Detects sparklings by their nametag, and by the sparkle-particle trail they leave before their name shows.
- **Line of sight only:** you're only alerted about critters you could actually see — nothing behind walls or underground, and never with coordinates.
- `/shiny history` keeps every sparkling you've caught, with dates, biome colours and a running total.

### 📋 Critter tracking
- A HUD checklist of all 37 critters across the Forest, Cavern, Icy and Haunted biomes, ticked off as you catch them (and as your party's loot shares come in).
- Critters you still need are outlined in the world; the outline disappears once you've caught one.
- `!missing` in party chat answers with what the run still needs.

### 🔎 Clearer highlights
- Oversized outlines that stand clear of the mob, with a minimum size so small critters (like the Gazer) are easy to spot.
- Outlined mobs are **tinted** toward their outline colour.
- Disguised Duplicos, shulkers and other tricky critters are outlined properly, with no flicker.
- Floor drops, rocks and punchable cases get their own outlines.
- Name labels only where the game doesn't already show one, and only when you can see them.

### 🐝 Bee nests & honeyhives
- Every bee nest nearby is outlined; emptied ones drop off the list.
- Full honeyhives get a **real beacon beam** while Miria's Contest is still incomplete, using what you can see plus your profile's hive refill times.
- `/shiny hives` lists what's known about each hive.

### 🧱 Snoozling walls
- Waypoints on all five Snoozling walls, with live distance. Each one disappears once its wall is broken.

### ⏱️ Runs, timers & the contest
- Run timer, per-biome splits and personal bests (`/shiny splits`).
- Miria's Contest countdown, with warnings at the minutes you choose while it's still incomplete.
- Safari Manager guard: refuses the manager click until your whole party is there (sneak to bypass).
- Quest prompts: with chat open, a click anywhere presses the quest's **Accept** button for you — exactly what clicking the button does. Switch off under *Quest prompts*.

### 👥 Party tools
- **Party dex:** looks up each member's Sparkling Critterdex as they join and works out which sparklings the whole party already has.
- `/sparkling <name>` shows any player's Hunting level, tickets, Safari Essence and sparklings.
- Optional hotspot relay and automatic hotspot splitting across the party.
- Shared timers (`!timer`) and downtime holds (`!dt`).
- **Locations never go to party chat:** anything that would give away a position is shown only to you.

### 🌍 Quality of life
- **Reload chunks key:** does what F3+A does, without the chat message — and you can hold it to keep reloading. You'll be asked to pick a key the first time you launch.
- Paintings are hidden inside the Critter Safari.
- Movable, resizable HUD panels (`/shiny hud`), with [Firmament](https://modrinth.com/mod/firmament)'s JARVIS HUD editor supported.
- Settings screen powered by MoulConfig, the same library Firmament uses.

---

## Installation

1. **Install Fabric** for Minecraft **26.1.2** — use the [Fabric installer](https://fabricmc.net/use/installer/), or any launcher that supports Fabric (Prism, Modrinth App, MultiMC…).
2. **Download these mods** and put them in your `mods` folder:

   | Mod | Where to get it |
   |---|---|
   | **Shiny Hunter** | [Latest release](https://github.com/javabetter/ShinyHunter/releases/latest) — `ShinyHunter-v2.0-26.1.2.jar` |
   | **Fabric API** | [Modrinth](https://modrinth.com/mod/fabric-api) |
   | **Fabric Language Kotlin** | [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) |

   Your `mods` folder is inside your Minecraft folder (`.minecraft/mods` in the vanilla launcher; in Prism, right-click the instance → **Folder** → `minecraft/mods`).
3. **Launch the game.** Shiny Hunter will ask you to pick a key for **Reload chunks** — press any key, or Esc to skip.
4. **Join Hypixel Skyblock** and head to the Critter Safari. Type `/shiny` to open the settings.

> Java 25 or newer is required, which Minecraft 26.1.2 already needs.

**Updating:** delete the old `ShinyHunter v….jar` from your `mods` folder before adding the new one — two copies will stop the game from starting.

---

## Commands

| Command | What it does |
|---|---|
| `/shiny` | Open the settings |
| `/shiny help` | List every command |
| `/shiny hud` | Drag and resize the HUD panels |
| `/shiny togglehud` | Show or hide the HUD |
| `/shiny reloadkey` | Pick the Reload chunks key |
| `/sparkling` | Your own Hunting level, tickets, Safari Essence and sparklings |
| `/sparkling <name>` | The same for another player (also `/shiny dex <name>`) |
| `/sparkling <a> <b> …` | Sparklings those players all have |
| `/sparkling party` | Re-run the shared-sparklings check for your party |
| `/shiny history [page]` | Every sparkling you've caught, newest first |
| `/shiny splits` | This run's splits and your bests |
| `/shiny resetpb` | Clear your saved best times |
| `/shiny hives` | What's known about each honeyhive |
| `/shiny mark` · `/shiny waypoints` | Drop a waypoint ahead of you · clear waypoints |
| `/shiny who` | Who the Safari Manager guard is counting |

### Party chat commands
Anyone in your party can type these; each one can be switched off in settings.

| Command | What it does |
|---|---|
| `!missing` · `!m [i\|f\|c\|h]` | What this run is still missing (optionally for one biome) |
| `!shared` · `!shared all` | Sparklings the whole party already has |
| `!shiny <name>` | A player's sparklings, posted to the party |
| `!timer <length>` | Start a shared timer |
| `!dt [reason]` | Hold the Safari Manager after this run until you say `r` |

---

## Fair play & privacy

Shiny Hunter is built to only tell you what you could see or look up yourself:

- **No through-wall information about mobs.** Alerts, outlines and labels for critters only appear when the critter is in view. The only markers drawn through walls are fixed scenery whose positions never change — bee nests, honeyhives and the Snoozling walls.
- **No coordinates in alerts,** and no positions ever sent to party chat.
- **Nothing happens without you.** The mod only sends what a player could: party-chat messages from the features you have switched on, and the quest Accept click when *you* click. Everything else stays on your screen.
- **Your account is safe.** Shiny Hunter never asks for, reads or sends your login. Player lookups (`/sparkling`, party dex) go through the Shiny Hunter API, a small proxy that holds a Hypixel API key so you don't need one. It only ever sends a player's UUID (looked up from their name with Mojang), and only receives that player's public Critter Safari stats.

> ⚠️ **Use at your own risk.** Shiny Hunter is a third-party mod and is not affiliated with or endorsed by Hypixel or Mojang. Hypixel's rules on modifications apply; check them yourself and switch off any feature you're unsure about.

---

## Building from source

```bash
git clone https://github.com/javabetter/ShinyHunter.git
cd ShinyHunter
./gradlew build
```

The jar appears in `build/libs/`. You'll need JDK 25.

Player lookups use the proxy address set as `api_proxy_url` in `gradle.properties`; leave it blank to build without player lookups.

---

## Credits

Made by **GamingLegend123**, with **Claude**.
Settings screen by [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig). HUD editing via [Firmament](https://github.com/nea89o/Firmament)'s JARVIS.

## License

[MIT](LICENSE)
