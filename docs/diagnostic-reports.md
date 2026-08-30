# Handling diagnostic reports

The diagnostics screen (long-press the version stamp on any screen) has a
**Send to Tawny** button. This is what happens to what it sends, and what to do
about it.

## What a report is

A one-shot JSON blob, sent only when the user taps the button:

| Field | Example |
|---|---|
| `log` | the flight-recorder text from the diagnostics screen — connection attempts, ICE state, timings, **hashed** room ids only |
| `v` / `c` | app `versionName` / `versionCode` |
| `model` | `Build.MODEL` |
| `android` | `Build.VERSION.RELEASE` |
| `id` | a random 6-char client-side id, so the same report sent twice can be spotted |
| `ts` | server timestamp, added on receipt |

**No IP, no country, no account, no persistent identifier, no pairing key.** The
privacy policy enumerates exactly this list; `rendezvous/reports.js` stores
exactly this list. If you add a field, the policy changes too.

## Where it lives

Workers KV, `REPORTS` namespace, key `report:<epoch-ms>:<rand>`, **30-day TTL**.
The TTL is the retention policy — reports delete themselves, there is nothing to
prune server-side and no way to forget to.

`POST /report` is origin-checked and rate-limited. With the `REPORTS` binding
absent it just 404s and the app falls back to the OS share sheet — nothing
breaks. Enable it by binding the namespace in `wrangler.toml` and setting
`REPORT_KEY` (see that file).

## Hearing about one

Set `REPORT_NOTIFY_URL` as a Worker secret and each stored report also fires a
one-line push:

```
wrangler secret put REPORT_NOTIFY_URL
```

- An **ntfy.sh** topic URL (`https://ntfy.sh/your-topic`) → plain-text push,
  free, installs as a phone app.
- Any **Discord / Slack-style webhook** → posted as `{content: "..."}`.

Best-effort: unset or failing, the report is still stored and still pullable.
There is deliberately **no cron / scheduled job** — nothing polls, nothing has
to stay running on a laptop.

## Pulling them

```
tools/tawny-reports          # drain: fetch + delete from the Worker, append locally
tools/tawny-reports --peek   # look without draining
```

Lands in `~/Documents/Tawny reports/YYYY-MM.ndjson`, one report per line, with a
`jq` summary printed. Config (`TAWNY_WORKER`, `TAWNY_REPORT_KEY`) in
`~/.config/tawny-reports.env`.

Draining is the default and the right choice: KV stays near-empty between
look-ins, and the local NDJSON is the working copy. **Keep that folder out of
any cloud backup, and delete old monthly files** — it should not outlive the
30-day server copy by much.

## Triage

Reports are anonymous by design, so there is **no reply loop** — anyone wanting a
response uses the About → Email row instead. For each report:

1. **Old build?** `v`/`c` behind current and the failure is already fixed → ignore.
2. **Environmental?** captive portal, both ends on carrier-grade NAT with TURN
   also blocked, corporate Wi-Fi → nothing to fix; note it if a pattern forms.
3. **New bug?** open a TODO with the log attached, reproduce, fix.
4. **Aggregate signal?** several reports from one OEM or one Android version →
   dig into that slice.

A throwaway `jq` pass over the NDJSON (count by `v`, by `model`, by first log
line) is the "is this getting better across releases" view.
