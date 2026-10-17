// Offline test for the Worker: Mojang and Hypixel are replaced with fakes, so this checks the
// Worker's own logic — access rules, trimming, caching, limits — with no network and no key.
// Run: node worker/test/test.mjs
import worker from "../src/index.js";

const UUID = "0123456789abcdef0123456789abcdef";
const OTHER = "fedcba9876543210fedcba9876543210";
let hypixelCalls = 0;
let lastHypixelKeyHeader = null;

globalThis.fetch = async (input, init = {}) => {
  const url = String(input);
  if (url.startsWith("https://api.hypixel.net/v2/skyblock/profiles?uuid=")) {
    hypixelCalls++;
    lastHypixelKeyHeader = init.headers && init.headers["API-Key"];
    const uuid = url.split("uuid=")[1];
    return new Response(JSON.stringify({
      success: true,
      profiles: [
        { profile_id: "a", cute_name: "Banana", selected: false, members: { [uuid]: { safari: { secret: 1 } } } },
        {
          profile_id: "b", cute_name: "Apple", selected: true,
          members: {
            [uuid]: {
              safari: { discovered_sparkling_critters: ["SOLSNATCHER"], tickets: { basic: 39 } },
              player_data: { experience: { SKILL_HUNTING: 1234.5, SKILL_MINING: 99 } },
              currencies: { coin_purse: 5000000, essence: { SAFARI: { current: 12212 }, GOLD: { current: 1 } } },
              foraging: { honey: { refill_times: { hive_8: 1 } }, other: true },
              bestiary: { kills: { doomspiral_1: 120, wumpa_1: 118, cavernfish_1: 300, zombie_1: 5 }, deaths: { zombie_1: 2 } },
              inventory: { big: "private stuff" },
            },
            [OTHER]: { safari: {} },
          },
        },
      ],
    }), { status: 200 });
  }
  throw new Error("unexpected fetch " + url);
};

const env = { HYPIXEL_KEY: "test-key-not-real" };
let failures = 0;
function check(name, condition) {
  console.log((condition ? "PASS " : "FAIL ") + name);
  if (!condition) failures++;
}
const AGENT = "Shiny Hunter Private";
const call = (path, init = {}) => worker.fetch(new Request("https://x.workers.dev" + path, init), env);
const get = (path, ip = "1.1.1.1", agent = AGENT) =>
  call(path, { headers: { "User-Agent": agent, "CF-Connecting-IP": ip } });

// health
check("health is ok", (await call("/health")).status === 200);

// old sign-in endpoint and non-mod callers
check("old /authenticate says it's gone", (await call("/authenticate", { method: "POST", body: "{}" })).status === 410);
check("request without the mod's User-Agent is refused", (await get("/profile/" + UUID, "1.1.1.1", "curl/8.0")).status === 404);
check("no Hypixel call for a refused request", hypixelCalls === 0);

// profile content
const ok = await get("/profile/" + UUID);
check("mod gets the profile", ok.status === 200);
const body = await ok.json();
const text = JSON.stringify(body);
check("key sent upstream as API-Key", lastHypixelKeyHeader === "test-key-not-real");
check("key never appears in the response", !text.includes("test-key-not-real"));
check("only the selected profile is returned", body.profiles.length === 1 && body.profiles[0].cute_name === "Apple");
const member = body.profiles[0].members[UUID];
check("safari dex kept", member.safari.discovered_sparkling_critters[0] === "SOLSNATCHER");
check("hunting xp kept", member.player_data.experience.SKILL_HUNTING === 1234.5);
check("safari essence kept", member.currencies.essence.SAFARI.current === 12212);
check("honey refills kept", member.foraging.honey.refill_times.hive_8 === 1);
check("safari bestiary kills kept", member.bestiary.kills.doomspiral_1 === 120 && member.bestiary.kills.cavernfish_1 === 300);
check("other bestiary entries dropped", !("zombie_1" in member.bestiary.kills) && !("deaths" in member.bestiary));
check("coin purse dropped", !text.includes("5000000"));
check("inventory dropped", !text.includes("private stuff"));
check("other skills dropped", !text.includes("SKILL_MINING"));
check("other members dropped", !(OTHER in body.profiles[0].members));

// caching
const before = hypixelCalls;
await get("/profile/" + UUID);
check("repeat lookup served from cache", hypixelCalls === before);

// looking up someone else (party dex) works
const other = await get("/profile/" + OTHER);
check("can look up another player", other.status === 200);

// rate limit: 30/min per IP (3 lookups used above)
let limited = false;
for (let i = 0; i < 40; i++) {
  if ((await get("/profile/" + UUID)).status === 429) { limited = true; break; }
}
check("per-IP rate limit kicks in", limited);
check("a different IP is unaffected", (await get("/profile/" + UUID, "2.2.2.2")).status === 200);

// upstream cap: many IPs asking for many uncached players can't exceed 50 Hypixel calls a minute
const startCalls = hypixelCalls;
let busy = false;
for (let i = 0; i < 80; i++) {
  const uuid = i.toString(16).padStart(32, "0");
  if ((await get("/profile/" + uuid, "10.0.0." + i)).status === 429) { busy = true; }
}
check("upstream cap stops a many-IP flood", busy && hypixelCalls - startCalls <= 50);

// Sparkling Mutuals uses the API under the same rules (cached player, fresh address)
check("Sparkling Mutuals is a known client", (await get("/profile/" + OTHER, "3.3.3.3", "Sparkling Mutuals")).status === 200);

// unknown path
check("unknown path is 404", (await call("/nope")).status === 404);

console.log(failures === 0 ? "\nALL PASSED" : `\n${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);
