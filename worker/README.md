# Shiny Hunter API proxy

A Cloudflare Worker that holds the Hypixel API key so the public mod never ships one.

- No sign-in: Mojang's session server refuses requests from Cloudflare Workers, so the proxy can't
  check accounts. It limits each IP to 30 lookups a minute, caps its own Hypixel calls at 50 a
  minute, and only answers requests carrying the mod's User-Agent.
- `/profile/<uuid>` returns only what Shiny Hunter reads — sparkling dex, tickets, captures,
  Hunting XP, Safari Essence, honey refill times. Inventories, purse, bank and other members are
  dropped inside the Worker.
- Free: Cloudflare's free plan covers 100,000 requests a day. No database, no custom domain.

## One-time setup (about 10 minutes)

You need Node.js (you have v24) and a free Cloudflare account.

1. **Create a Cloudflare account** at https://dash.cloudflare.com/sign-up (free plan, no card).

2. **Open a terminal in this folder** (`shinyhunter 26.1.2\worker`) and log in:

   ```
   npx wrangler login
   ```

   A browser tab opens to authorise Wrangler, Cloudflare's command-line tool.

3. **Deploy:**

   ```
   npx wrangler deploy
   ```

   It prints your address, e.g. `https://shinyhunter-api.<your-subdomain>.workers.dev`.
   The first time, Cloudflare asks you to pick that `<your-subdomain>` name.

4. **Add the secret.** The command asks you to paste the value; it's stored encrypted and never
   appears in any file.

   ```
   npx wrangler secret put HYPIXEL_KEY
   ```

   `HYPIXEL_KEY` is your Hypixel key (see "Production key" below). (`TOKEN_SECRET` from older
   versions is no longer used; `npx wrangler secret delete TOKEN_SECRET` removes it.)

5. **Check it's up:** open `https://shinyhunter-api.<your-subdomain>.workers.dev/health` in a
   browser. It should say `{"ok":true}`.

6. **Point the mod at it:** in `gradle.properties` set

   ```
   api_proxy_url=https://shinyhunter-api.<your-subdomain>.workers.dev
   ```

   (no trailing slash) and rebuild. The public edition then uses the proxy; the private edition
   keeps using your own key if one is set, and falls back to the proxy if not.

## Production key

Developer keys expire after a few days, which is fine for testing but not for a released mod.
For release, apply for a production key at https://developer.hypixel.net — create an application,
describe Shiny Hunter and what it reads, and wait for approval. When it arrives, run
`npx wrangler secret put HYPIXEL_KEY` again with the new key; no redeploy or mod update needed.

## Updating

Edit `src/index.js`, run `node test/test.mjs` (offline, no key or network needed), then
`npx wrangler deploy`. Secrets carry over.

## Endpoints

| Method | Path              | Needs                     | Returns                              |
|--------|-------------------|---------------------------|--------------------------------------|
| GET    | `/health`         | —                         | `{"ok":true}`                        |
| GET    | `/profile/<uuid>` | mod's User-Agent          | trimmed `/v2/skyblock/profiles`      |

Limits: 30 lookups per minute per IP, 50 Hypixel calls per minute overall, 60-second cache.

## Watching it

`npx wrangler tail` streams live logs. The Cloudflare dashboard shows daily request counts
against the 100,000 free limit.
