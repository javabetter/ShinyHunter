/**
 * Shiny Hunter API proxy.
 *
 * Holds the Hypixel API key so the mod never has to. There is no login: Mojang's session server
 * sits behind a firewall that refuses requests from Cloudflare Workers, so "prove you own this
 * account" can't be checked from here. Abuse is contained instead by what's on offer and how much:
 * only trimmed, public Safari data; a per-IP limit; a cap on how often this Worker calls Hypixel at
 * all; and a cache so repeats never reach Hypixel. The key itself never leaves the Worker.
 *
 * Endpoints
 *   GET  /profile/<uuid>  -> trimmed /v2/skyblock/profiles
 *   GET  /health          -> {"ok": true}
 *
 * Secret (set with `wrangler secret put`, never in this file):
 *   HYPIXEL_KEY   your Hypixel API key
 *
 * Built to stay on Cloudflare's free plan: no database, trimmed responses, in-memory cache.
 */

const HYPIXEL_PROFILES = "https://api.hypixel.net/v2/skyblock/profiles?uuid=";

/** How long a profile is reused before asking Hypixel again. */
const CACHE_MILLIS = 60 * 1000;

/** Per-IP ceiling, per minute, on profile lookups. Generous for play, useless for scraping. */
const LOOKUPS_PER_MINUTE = 30;

/**
 * Ceiling, per minute, on calls this Worker instance makes to Hypixel (cache misses only). Keeps a
 * flood from many addresses from burning through the key's own Hypixel limit.
 */
const UPSTREAM_PER_MINUTE = 50;

/**
 * Requests must come from a known mod; a cheap filter that turns away random scanners. Sparkling
 * Mutuals uses this API too, with the same limits.
 */
const CLIENT_AGENT = /^(Shiny Hunter|Sparkling Mutuals)/;

/**
 * In-memory state. A Worker isolate lives for a while and serves many requests, so these do real
 * work, but they're best-effort: a fresh isolate starts empty, which is fine for a cache and a
 * soft rate limit and is why nothing here is relied on for correctness.
 */
const profileCache = new Map(); // uuid -> { at, body }
const rateWindows = new Map(); // ip -> { windowStart, count }
const upstreamWindow = { windowStart: 0, count: 0 };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true });
      }
      if (url.pathname === "/authenticate") {
        // Older mod builds signed in first; tell them plainly that's gone.
        return json({ error: "sign-in is no longer used; update Shiny Hunter" }, 410);
      }
      const match = url.pathname.match(/^\/profile\/([0-9a-fA-F-]{32,36})$/);
      if (request.method === "GET" && match) {
        return await profile(request, env, match[1].replace(/-/g, "").toLowerCase());
      }
      return json({ error: "not found" }, 404);
    } catch (error) {
      // Never echo internals: the message could mention the upstream request.
      console.error("Unhandled error", error && error.stack ? error.stack : error);
      return json({ error: "internal error" }, 500);
    }
  },
};

// ------------------------------------------------------------------ profiles

async function profile(request, env, uuid) {
  if (!CLIENT_AGENT.test(request.headers.get("User-Agent") || "")) {
    return json({ error: "not found" }, 404);
  }
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  if (!allow(rateWindows, ip, LOOKUPS_PER_MINUTE)) {
    return json({ error: "slow down" }, 429);
  }
  if (!env.HYPIXEL_KEY) {
    return json({ error: "server not configured" }, 500);
  }

  const cached = profileCache.get(uuid);
  if (cached && Date.now() - cached.at < CACHE_MILLIS) {
    return json(cached.body);
  }

  if (!allowUpstream()) {
    return json({ error: "busy, try again shortly" }, 429);
  }
  const upstream = await fetch(HYPIXEL_PROFILES + uuid, { headers: { "API-Key": env.HYPIXEL_KEY } });
  if (upstream.status === 429) {
    return json({ error: "Hypixel rate limit, try again shortly" }, 429);
  }
  if (upstream.status !== 200) {
    // Only Hypixel's one-line "cause" is passed on (e.g. "Invalid API key"); nothing else of the body.
    const text = await upstream.text();
    let cause = "";
    try {
      const failed = JSON.parse(text);
      cause = typeof failed.cause === "string" ? failed.cause.slice(0, 120) : "";
    } catch {
      // not JSON: a firewall page or similar — its opening words say which
      cause = "non-JSON: " + text.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim().slice(0, 120);
    }
    return json({ error: `Hypixel returned ${upstream.status}`, cause }, 502);
  }
  const full = await upstream.json();
  if (!full || full.success !== true) {
    return json({ error: "Hypixel refused the request" }, 502);
  }

  const body = trim(full, uuid);
  profileCache.set(uuid, { at: Date.now(), body });
  pruneCache();
  return json(body);
}

/**
 * Only what Shiny Hunter reads, in the same shape Hypixel returns it, so the mod parses the
 * proxy's answer and Hypixel's with the same code. Anything else in a profile — inventories,
 * purse, bank, other members — never leaves this Worker.
 */
function trim(full, uuid) {
  const profiles = [];
  for (const profile of Array.isArray(full.profiles) ? full.profiles : []) {
    if (!profile || profile.selected !== true) {
      continue;
    }
    const member = profile.members && profile.members[uuid];
    if (!member) {
      continue;
    }
    profiles.push({
      profile_id: profile.profile_id,
      cute_name: profile.cute_name,
      selected: true,
      members: {
        [uuid]: {
          safari: member.safari || {},
          player_data: { experience: { SKILL_HUNTING: pick(member, "player_data", "experience", "SKILL_HUNTING") } },
          currencies: { essence: { SAFARI: pick(member, "currencies", "essence", "SAFARI") } },
          foraging: { honey: pick(member, "foraging", "honey") },
          bestiary: { kills: safariKills(pick(member, "bestiary", "kills")) },
        },
      },
    });
  }
  return { success: true, profiles };
}

/**
 * The 37 Safari critters' bestiary ids (NEU's constants/bestiary.json, "safari"). Their kill counts
 * are a player's own catches; Doomspiral, Wumpa and Hideyho appear once a run, so their highest
 * count is the number of runs. Every other bestiary entry is left out.
 */
const SAFARI_BESTIARY = new Set([
  "cavernfish_1", "chuckwalla_1", "driftling_1", "flitter_1", "gemzie_1", "rockmite_1", "scrappy_1",
  "shyworm_1", "snoozle_1", "bluebird_1", "fluffling_1", "foxtrot_1", "hideonfloor_1", "honeybug_1",
  "macaw_1", "parakeet_1", "treefrog_1", "woodchucker_1", "areita_1", "bloodbat_1", "doomspiral_1",
  "duplico_1", "gazer_1", "gimmiegold_1", "hideonwall_1", "hideyho_1", "litterbug_1", "solsnatcher_1",
  "billygoat_1", "mantis_shrimp_1", "nozzlenose_1", "polaris_1", "shuddersquid_1", "strongarm_1",
  "tepid_1", "troodon_1", "wumpa_1",
]);

function safariKills(kills) {
  const out = {};
  if (kills && typeof kills === "object") {
    for (const id of SAFARI_BESTIARY) {
      if (typeof kills[id] === "number") {
        out[id] = kills[id];
      }
    }
  }
  return out;
}

function pick(object, ...path) {
  let node = object;
  for (const key of path) {
    if (node == null || typeof node !== "object") {
      return undefined;
    }
    node = node[key];
  }
  return node;
}

// ------------------------------------------------------------------ limits

function allow(windows, id, limit) {
  const now = Date.now();
  const window = windows.get(id);
  if (!window || now - window.windowStart >= 60_000) {
    windows.set(id, { windowStart: now, count: 1 });
    if (windows.size > 5000) {
      pruneWindows(windows, now);
    }
    return true;
  }
  window.count++;
  return window.count <= limit;
}

function allowUpstream() {
  const now = Date.now();
  if (now - upstreamWindow.windowStart >= 60_000) {
    upstreamWindow.windowStart = now;
    upstreamWindow.count = 0;
  }
  upstreamWindow.count++;
  return upstreamWindow.count <= UPSTREAM_PER_MINUTE;
}

function pruneWindows(windows, now) {
  for (const [id, window] of windows) {
    if (now - window.windowStart >= 60_000) {
      windows.delete(id);
    }
  }
}

function pruneCache() {
  if (profileCache.size < 500) {
    return;
  }
  const cutoff = Date.now() - CACHE_MILLIS;
  for (const [uuid, entry] of profileCache) {
    if (entry.at < cutoff) {
      profileCache.delete(uuid);
    }
  }
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
