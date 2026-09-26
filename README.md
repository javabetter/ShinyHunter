# Shiny Hunter
Shiny Hunter is a Critter Safari companion mod for Hypixel Skyblock! It includes a **_ton_** of useful features ~~and even more useless ones~~, such as a detecting when a sparkling critter is within line-of-sight, a custom jarvis-compatible HUD, party commands, player API checks, contest reminders, and much, much more...

## Features
### Sparkling alerts
- Displays a title, chat message, and audio when a sparkling critter is detected
- Detection picks up particles and nametags of sparklings (NOTE: to comply with Hypixel rules, it will only notify the player if the particles are within unobstructed line-of-sight of the player. The nametag detection works as long as the nametag is on your screen.)
- `/shiny history`keeps track of every sparkling you catch, along with dates and a running total. Colored per-biome for your viewing pleasure!

### Critter tracking
- Per-biome HUD checklist for each unique critter that has not been caught that run. Automatically checked off if any player in the party catches a mob of that type.
- If a unique critter of a certain type (e.g. Rockmite) has not been caught yet that run, all critters of that type will be outlined. This makes seeing remaining uniques for hunting skill exp _**WAY**_ easier!
- `!missing`, `!missing <biome>`, or `!m <first letter of biome>` in party chat will announce what mobs have not yet been caught in a biome. Super convenient for calling out your party for skipping something...

### Highlights, outlines, and waypoints
- Clear, customizable outlines and highlights for each mob, including disguised Duplicos, Hideonfloors, and other tricky mobs.
- Floor drops, rockmite rocks, and Honeybug nests are outlined
- Waypoints for Snoozle walls

### Contest tracking
- Never again experience the utter depression of missing a Miria's Contest with Shiny Hunter's contest timer HUD!
- If an incompleted contest has 5 minutes or less remaining, a reminder will be sent in chat along with a bell sound
- When a contest has been completed, it will mark as finished automatically
- Full bee nests on Torrhus canyon will be highlighted with a beacon beam when a contest is incomplete. Bee nests will not be highlighted if the contest has already been completed or if they are empty

### Party tools
- `/sparkling <username>` show's a players relevant safari stats from Hypixel's API, including sparklings caught, number of tickets (with rarity), hunting level, and more
- Party sparkling checker: automatically runs `/sparkling` for the player that because why not
- `!shared` will check the sparklings of all players in the party, then send the shared ones in party chat so your party can skip them! (This automatically runs when a party is filled)
- A decent amount of relevant party commands, including `!dt` and `!timer`

### Miscelaneous
- Reload chunk keybind: set a keybind to spam reload chunks. This basically mimics the behavior of F3+A to allow you to see hitboxes through unrendered chunks, but without the chat spam
- Paintings are hidden in the safari to make Hideonwalls easier
- Movable, resizable HUD `/shiny HUD` powered by jarvis
- Config menu powered by MoulConfig, the same library [Firmament](https://modrinth.com/mod/firmament) and other mods use
- Click anywhere when chat is open to accept Hideyho quest

# Commands

| Command | What it does |
|---|---|
| `/shiny` | Opens the settings/config menu |
| `/shiny help` | List every command |
| `/shiny hud` | Opens the HUD screen |
| `/shiny togglehud` | Toggles the HUD on/off |
| `/shiny reloadkey` | Set the keybind to reload chunks |
| `/sparkling` | Check your tickets, sparklings, and hunting level |
| `/sparkling <username>` | Check another player's tickets, sparklings, and hunting level |
| `/sparkling party` | Check the shared sparklings of the party |
| `/shiny history` | Shows all previous sparklings caught, with timestamps |
| `/shiny splits` | Current run's splits (if applicable), and your personal bests |
| `/shiny resetpb` | Clear your saved best times |

# Party commands

| Party Command | What it does |
|---|---|
| `!missing`·`!m` | Says missing unique critters for current run. |
| `!m <c/f/i/h>` | Says missing unique critter for a specific biome |
| `!shared` | Says shared sparklings of the party |
| `!shiny <username>` | Sends `/sparkling <username>` to party chat |
| `!timer <time>` | Start a timer and send a message when it ends |
| `!dt <reason>` | Display a downtime message when run ends, triggered when someone leaves the Critter Safari |

# Other info
## ⚠️Please note: use at your own risk!
This mod should obey Hypixel's rules, but if it does not I am not responsible. If any features are in violation, please let me know and I will update the mod to remove or change them ASAP!

## I am currently working to get a permanent Hypixel Developer API key.
In the meantime, I get a new temporary one every 5 days. This means that there is a chance the mod's key may be stale for a short period until I switch it out. You won't need to update the mod, just message me on Discord (name is `javabetter') and I will change it as soon as I can!

## License
[MIT](LICENSE)
