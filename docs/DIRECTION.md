# Tawny — direction brief

_Written 2026-08-29. Audience: Opus, who owns the changes. This points a
direction; it is not a task list to follow literally. Nothing here was
implemented — every item is Opus's call to scope, sequence, and revise._

Tawny when this was written: v0.2.0 / code 9 (now v0.3.1 / code 11, targetSdk 36).
Native Kotlin shell (`MainActivity.kt`, ~3250 LOC,
all programmatic views) + a WebView web client (`public/`, bundled in the APK)
that owns the WebRTC stack. Two roles — **Monitor** (stays with the pet, streams
cam+mic) and **Viewer** (watches, talk-back, chime, snapshot). Pairing is a QR /
`tawny://pair` link carrying a 128-bit channel key; no account. Transport is
LAN-first (Monitor runs an embedded relay in `LocalWeb.kt`), else STUN, else a
Cloudflare Worker rendezvous (`rendezvous/`), else TURN. Media is DTLS-SRTP end
to end; relays only ever see `sha256(key)`. iOS (`ios/`) is a stale stub.

The security work is genuinely good (challenge-response LAN admission,
fail-closed rendezvous, SAS, CSP, probes in `tools/probes/`). The reliability
work is careful (capture-loss detection, ICE restart, bfcache recovery, SAS
retry, transport failover). This brief is about what's left, and about the
business.

---

## Part 1 — Balances & checks (function, reliability, stability)

### A. Screen-off / no foreground service — the #1 product risk

The Monitor's camera stops the instant the app is backgrounded or the screen
locks. The app now *detects* this and shows viewers "paused" instead of a frozen
frame — good — but the core job ("leave a phone watching my pet for hours") is
only met while that phone is awake, foregrounded, screen on, on a charger.
`FLAG_KEEP_SCREEN_ON` + a dim mode is the current mitigation.

Every competitor (Alfred, Barkio) solves screen-off. This is the single thing
that separates Tawny from "a real pet monitor," and reviews will hammer it.

**Direction:** decide between (a) ship v1 honest about "keep the screen on" and
put a proper foreground service (`camera|microphone` FGS type + a libwebrtc
capture path outside the WebView + the Play FGS declaration + its own
disclosure) on the very next milestone, or (b) build the FGS path now. Leaning
(a) to ship, (b) immediately after — but (b) is the product. A middle step worth
taking regardless: a persistent notification while a session is live
(`POST_NOTIFICATIONS`), which is the honest signal that a phone is acting as a
camera.

#### Decision (2026-08-30): (a) confirmed. Battery mitigation landed; FGS deferred.

Reviewed while doing the Monitor battery pass. **(a) stands**, and the deciding
argument is not schedule — it is that *a foreground service on its own does not
buy screen-off capture here.*

An FGS with `camera|microphone` lifts the **platform's** UID-level ban on
background camera use. It does nothing about the **WebView**, which is what
actually owns the capture: Chromium tears its capture pipeline down when the
Activity stops and the window's surface goes away, and the page already sees
this today (`watchLocalTracks` → `S.captureLost` → the "paused" banner). So the
real work item is not "add a Service class" — it is "move capture out of the
WebView and onto libwebrtc", which this brief already said above and which is a
rewrite of the streaming core, not a submission-week patch.

Two further reasons not to fold it into the in-flight submission:

- **The Play cost is partly a human deliverable.** New FGS types require the
  Foreground Service declaration form *per type*, each wanting a short demo
  video (a hosted URL) showing the feature in use, plus a new prominent
  disclosure and the `POST_NOTIFICATIONS` surface. The video has to be recorded
  by a person. Declaring an FGS whose behaviour does not match the shipped build
  is a well-known rejection and post-publication-removal cause.
- **The submission is staged and self-consistent.** Runbook, `PLAY-SUBMISSION`,
  review notes and the ship pack all currently say "no service, keep the screen
  on", and that is true. Flipping it means editing ~9 documents plus the Console.

**What landed instead** (no new permission, no service, no doc claim changed):
dim mode now pins window `screenBrightness` to `0.004` so the backlight really
goes dark, the camera track and encoder drop to a low-power profile while it is
showing, the preview and the page's animations stop, and the level meter falls
from the panel refresh rate to 4 Hz. Most of the practical battery win, none of
the submission risk. See the power section of `MainActivity.kt` and `POWER` in
`public/app.js`.

**Concrete plan for the FGS milestone**, in order:

1. **Prove the premise first, before any Play paperwork.** Spike a throwaway
   `camera`-type FGS on top of today's WebView build and check whether capture
   actually survives a real screen-off on hardware (watch for the `paused` diag
   line). If it survives, the milestone is small. If it does not — the expected
   outcome — the milestone is the libwebrtc capture path, and should be sized
   as such. Everything below is wasted until this is answered.
2. **Manifest** (`android/app/src/main/AndroidManifest.xml`): add
   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
   `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`; declare
   `<service android:name=".MonitorService" android:exported="false"
   android:foregroundServiceType="camera|microphone" />`.
3. **`MonitorService.kt`**: started from `beginLive()` *while the Activity is
   foreground* — on API 34+ a `camera`-type service started from the background
   throws, so this ordering is load-bearing. `startForeground(id, notification,
   FOREGROUND_SERVICE_TYPE_CAMERA or FOREGROUND_SERVICE_TYPE_MICROPHONE)`.
   Stopped in `endLive()` and `onDestroy()`.
4. **Notification**: its own low-importance channel ("Monitor running"), ongoing,
   text "Tawny is streaming from this phone's camera", tapping returns to the
   Activity, with a "Stop" action wired to `endLive()`. This is the honest
   signal that a phone is acting as a camera and should ship even if the
   screen-off path slips.
5. **Disclosure**: reuse the `disclose()` pattern in `MainActivity.kt` with a
   second, screen-off-specific string — proposed: *"To keep watching with the
   screen off, Tawny needs to keep using this phone's camera and microphone
   while it is not on screen. A notification will show for as long as it is
   streaming. Video and sound are still sent encrypted, directly between your
   devices, and are never recorded or stored."* Shown before the first
   screen-off session, not at install.
6. **`POST_NOTIFICATIONS`** runtime request on API 33+, asked at the same
   moment, and degrade gracefully when refused (the service still runs; Android
   shows its own notice).
7. **Play Console**: Foreground Service declaration for *both* `camera` and
   `microphone` — purpose, a user-visible description, and a demo video URL
   each; refresh the prominent-disclosure answers; re-check Data Safety (no new
   data types, so likely unchanged).
8. **Docs, in the same commit as the code**: runbook §7 + "Honest limitations" +
   Step 6 (add the FGS declaration answers), `PLAY-SUBMISSION.md` §2 and §7 and
   its residual-questions list, `docs/review-notes.md`, this section (mark
   done), the store "Good to know" copy, and the ship pack (`listing/review-
   notes.md`, `listing/category-and-contact.md`, `listing/data-safety-answers.md`
   if it moves, `README.md`). Runbook stays canonical; the ship-pack
   `listing/*.md` mirror it and carry a "Last synced" header.

### B. TURN — ✅ RESOLVED (provisioned on Cloudflare Realtime)

_Was: `/turn` returned 404, so two peers both on cellular / behind CGNAT simply
failed to connect and "watch from anywhere" silently didn't work for a
meaningful slice of users._

A Cloudflare Realtime TURN key is now provisioned and the secrets
(`TURN_KEY_ID`, `TURN_API_TOKEN`) are set on the Worker, so `/turn` issues
short-lived credentials to any caller holding a ticket valid for the room. The
CGNAT-to-CGNAT case now falls back to an encrypted relay instead of failing, and
media across it stays DTLS-SRTP — the relay forwards packets it cannot read.
Nothing in the app changed: it already sent `turnMode:"auto"` and fetched
`/turn` at runtime, so this needed no rebuild or resubmission.

**What remains is the cost line, not the function.** The free tier is 1 TB/month
of relayed egress (~1,400–2,000 hours of relayed video), then $0.05/GB.
`turnMode:"auto"` keeps the relay out of the path whenever a direct one exists,
so only genuinely CGNAT-bound calls draw on it — but this is now a real,
metered, recurring bill, which is exactly what Part 2's optional-support story
exists to point at. It is also the number the optional fair-use soft cap would
be measured against, if the economics ever need it. Revisit if relayed minutes
approach the ceiling.

### C. Rendezvous security patch is written, tested, NOT deployed

Per the project memory the user chose "hold off" on `wrangler deploy`. The
deployed worker still lets an attacker who knows only the room id re-key the room
and lock out the real Viewer (4/6 on `tools/probes/rendezvous-admission.mjs`; the
code in `rendezvous/` is 6/6). Low effort, high value. Confirm the deployed
version, then ship `rendezvous/` as-is before any public launch.

### D. `MainActivity.kt` is one 3250-line Activity, all programmatic views

It's coherent and it works, but it's where the lifecycle bugs have lived (the
memory log is full of recreate/consent-survival/screen-restore/theme-flip fixes).
If Opus is going to be living in this file, break screens into
functions/files — or migrate the pure-native screens to Compose — **only as you
touch them**. No big-bang rewrite; the churn risk outweighs the tidiness.

### E. No automated tests beyond the handshake probes

`tools/probes/` is excellent — keep it, extend it. Gaps: no instrumented Android
test, no JS tests for the `app.js` signalling state machine (`handle()` /
`openSignal()` — intricate, has regressed repeatedly). Direction: a small Node
harness that drives two fake peers through `rendezvous/room.js` for the common
flows (join order, Monitor drop/rejoin, 5-viewer cap, ticket expiry, re-key),
plus a documented **on-device manual matrix** in `docs/` that the user runs on
the A50 + T10Pro: same-WiFi; Monitor-WiFi/Viewer-cellular; both-cellular;
background mid-call; network drop mid-call; 5 viewers; rename mid-session; theme
flip mid-session. Wire the Node harness into CI once the repo has a remote.

### F. `server.js` (self-host reference) is knowingly stale

Still has the write-once ticket bug and the old RELAY set. Either bring it to
parity with `rendezvous/room.js` or mark it "example only, not for production"
loudly in the README so a self-hoster doesn't ship the buggy one.

### G. Launch blockers (still true, from memory)

1. **No upload keystore** — `android/keystore.properties` absent → `bundleRelease`
   silently emits an *unsigned* AAB. User must do this (their password).
2. **Privacy policy not hosted.** Page is written.
3. **Trademark search on "Tawny" never done** (alternates on file: Hoot, Perch).

Opus can't do 1 or 3, but **can shrink the list**: serve the privacy policy from
the Worker (`GET /privacy`) so there's no GitHub Pages dependency; commit a
sanitized copy of the submission runbook into the repo (it currently lives only
in `~/Documents/Tawny ship/`, outside git — a single point of failure).

### H. Smaller items to sweep (low priority)

- `app.js` `adopt()` takes `t` into `S.token` with no charset/length guard.
  Admission-only, low risk — tighten anyway.
- `TawnyNative` isn't origin-scoped (accepted in `SECURITY.md`); add a guard
  comment so a future iframe doesn't silently expose it.
- All user-facing strings are hardcoded English in Kotlin + `app.js`.
  String-externalize the native screens even if you don't localize yet, and do
  one TalkBack pass on welcome → role → pair → live.
- Plan a `targetSdk` 36 bump before Play's next deadline.
- `androidx` deps are current enough for now; no action.

### I. Chime sounds — done, one carries a credit

The Viewer's chime is now five pet-calling sounds instead of three UI beeps —
**Dog toy, Psp psp psp, Meow, Good boy, Bell** (`public/sounds/`, played by
`playChime()` in `public/app.js`, each with an oscillator fallback in
`synthChime()`).

Four are real recordings, kept as masters in `public/sounds/_src/` and trimmed
by `tools/gen-chimes.sh`; `bell` stays FM-synthesised. Licences — full table in
`public/sounds/README.md`:

- **Dog toy**, **Good boy** — Pixabay Content License, no attribution.
- **Meow** — freesound.org 582745, CC0.
- **Psp psp psp** — freesound.org 654284 by Jolindi, **CC BY 4.0**: credited in
  the About screen (`showAbout()` in `MainActivity.kt`). Swap for a CC0 clip to
  carry no attribution at all.
- **Bell** — synthesised, the project's own.

Not a launch blocker. Nobody has heard the clips on a device yet — check them in
the on-device test pass.

---

## Part 2 — Monetization

### The category and why users distrust its pricing

Old-phone-as-pet-cam apps — Alfred (90M+ installs, free + ads, ~$30/yr Premium
removes ads and unlocks 1080p/zoom/longer history), Barkio / Dog Monitor by
TappyTaps (free trial → subscription), plus Pet Monitor VIGI and the
hardware+cloud players (Furbo/Petcube, $4–7/mo for recording history). The
recurring complaints, straight from reviews:

1. **Bait-and-switch trials** — "try free" that charges in 3 days, with
   cancellation friction. This is the loudest complaint in the category (Barkio
   reviews specifically) and a genuine trust problem.
2. **Core function paywalled** — resolution, device count, session length gated
   behind a subscription; users feel the app is holding their pet hostage.
3. **Ads over a live pet feed** — uniquely irritating, and a privacy smell in a
   camera app.
4. **The whole business is your home video in their cloud** — the exact trade
   Tawny is built to avoid.
5. **No explanation of why it costs money** — so any charge reads as rent-seeking.

Tawny's architecture answers all five: P2P, E2E-encrypted, no cloud recording,
no account, no ads. The only real recurring cost is rendezvous + TURN egress for
the remote path. That is a story you can tell honestly: **pay if you want the
project to keep existing and to help cover the relay bandwidth your remote
viewing uses.**

### What actually works (evidence)

- Freemium-with-subscription dominates non-game apps (subscriptions ≈ 82% of
  non-game store revenue). Hard paywalls convert trials better (~78% vs ~45%) —
  but that is exactly the mechanic the category is hated for, so it's off the
  table here by choice.
- Pure donationware (Signal, Syncthing, Organic Maps) sustains *mission-driven*
  projects but converts low; Signal runs on a few big donors + a foundation, not
  app-store tips.
- Tailscale added a "Personal Plus" tier partly as a way to support them — and
  found people bought it purely to support them — then concluded a genuinely
  better paid tier is a stronger *long-term* model than a pure-goodwill tier.
  Lesson: goodwill alone works at small scale; to grow it, attach a small perk
  that doesn't gate core function.
- One-time "lifetime / supporter" IAPs are a well-worn indie pattern; they work
  best carrying a tiny cosmetic perk (badge, themes, early-access) so stores
  accept them and buyers feel acknowledged.
- **Store-policy reality:** Google Play requires Play Billing for anything
  digital sold in-app; donations *outside* Play Billing are allowed only for
  registered nonprofits. A solo/for-profit dev therefore sells "support" as a
  normal IAP/subscription through Play Billing (Play's cut is 15% up to
  $1M/yr). Apple, when iOS revives, rejects *pure* donation IAPs with zero
  digital benefit (guideline 3.2.1) — so design a token perk now that satisfies
  both stores.

### The model — mapped to the three requirements

**1. Nothing functional behind a paywall, ever.** The free tier is the whole
app: unlimited devices (the 1+5 cap is a design choice, not a price lever),
unlimited session length, full resolution, all lenses/zoom, chimes, snapshots,
talk-back, **and the remote path including TURN**. No ads, no account, no
analytics — keep this as a marketing pillar, it's the differentiation.

**2. An optional subscription that only makes sense as "help with costs."**
Call it **"Tawny Supporter"** — monthly and annual, deliberately cheap
(~$1.99/mo, ~$15/yr, point people at annual). It's a contribution, not a price.
Copy is the honest cost story: *"Tawny is free and always will be. Watching your
pet from outside your home uses a relay we pay bandwidth for. If Tawny is useful
to you, chip in — it keeps the relay running."* Perks are **non-gating
acknowledgements only**: a supporter badge on the pairing screen, the full theme
set / an exclusive owlet colourway, a supporters' changelog note, priority on
the support email. Nothing a free user can't do.

**3. A one-time lifetime payment to fund development.** **"Tawny Forever"** — a
single non-consumable IAP (~$20–30). The Supporter perks, permanently, plus an
opt-in name in an in-app credits list and an early-access/beta track. Framing:
*"A one-off way to say this was worth building. Pays for the next feature, not a
subscription you'll forget to cancel."* This directly answers the category's
"hard-to-cancel subscription" complaint — some users specifically want to pay
once.

**Optional: a fair-use soft cap** (only if you want the unit economics to
provably close). Count *relayed* remote-minutes per channel per month on the
device / rendezvous — never tied to identity; LAN is always unlimited and
uncounted. A free household gets, say, 30–60 hours/mo of rendezvous-brokered or
TURN viewing; past that, remote viewing still works but at reduced bitrate, with
a gentle "you're a heavy remote user — Supporter keeps this uncapped" note. This
keeps the free tier genuinely complete for ~99% of users while making the people
who *are* the cost the ones most likely to contribute — and the cap is literally
the cost line, so it's honest. If it feels against the spirit, skip it; at
pet-monitor scale on Cloudflare's free tier, "donations ≥ relay bill" is a low
bar for a long time.

### Implementation notes for Opus

- Play Billing Library 7+. One subscription (monthly + annual base plans) + one
  non-consumable. Keep it **tiny and skippable**: reachable from an unobtrusive
  "Support Tawny" row in the sessions-home overflow. Never a modal, never on
  launch, never right after a call.
- **No entitlement backend.** Verify locally with Play Billing's cached purchase
  state; store `supporter=true` in prefs. Losing it on reinstall is fine (Play
  restores purchases). Building an account system to track this would break the
  privacy pitch.
- Entitlement is cosmetic, so spoofing it doesn't matter — which is *why* you
  never need server verification. Lean into that.
- Add a short in-app "Where your money goes" screen (relay bandwidth, Play's
  cut, developer time). Transparency is the conversion mechanism here, not a
  feature-comparison table.
- Keep a fully-functional build with billing compiled out for F-Droid / sideload
  if the user wants the open-source-goodwill audience. Cheap, buys trust.
- iOS later: StoreKit 2, same two products; the cosmetic perk already satisfies
  Apple's "no pure donations" rule.

---

## Part 3 — Further changes for success (opinion, ranked by leverage)

1. **Fix the "keeps working after the screen goes off" story** (see 1A). Product,
   not polish. Everything else is second.
2. **Ship the rendezvous patch** (1C). TURN is now provisioned (1B ✅), so the
   headline feature works end to end; the admission patch is the last piece of
   this that is written, tested and still not deployed.
3. **Shrink the launch blockers Opus can touch** (1G): privacy policy on the
   Worker, submission runbook into the repo, a small public status/health page.
   Leave keystore + trademark to the user as the only two remaining.
4. **Test matrix + two-peer CI harness** (1E). The relay and the signalling
   state machine have regressed repeatedly; lock them down.
5. **Onboarding: the "cold Monitor" case.** The most common failure is a Viewer
   connecting before the Monitor is up ("Monitor isn't on yet"). Make the
   rendezvous hold the room and auto-connect the Viewer the instant the Monitor
   appears (partly there); add a one-line first-run card: "Set up the Monitor
   phone first, plug it in, then scan from your phone."
6. **Store trust surface:** a 20-second "how it works" (P2P, encrypted, no
   account, no cloud) on the listing and first-run; screenshots showing a real
   pet, the QR, the live view with talk-back, the "no account" claim; canned
   answers for the category's stock questions ("does it record?" "is my video on
   your servers?" — no, here's why).
7. **Do the trademark search** before locking the listing. Cheap insurance;
   alternates already chosen.
8. **iOS.** The `ios/` tree is stale. This product *requires two phones*, and
   households are frequently mixed iOS/Android — so cross-platform is worth more
   here than for a typical app. The web client already owns WebRTC, so it's a
   shell + StoreKit job, not a rewrite. Highest-leverage growth item once the
   core is solid.
9. **Privacy-safe retention hooks:** a local "last seen" thumbnail on the
   sessions-home card; an optional on-device motion/sound alert that buzzes the
   Viewer when the room gets loud. Genuinely useful, stays true to the
   architecture, no cloud.
10. **Don't over-build.** The winning position is *"the pet monitor that doesn't
    spy on you, doesn't nag you to pay, and just works on two phones you already
    own."* Test every proposed change against that sentence; if it doesn't
    defend it, drop it.

---

## Suggested sequence

1. Deploy the rendezvous patch; ~~provision TURN~~ (done); privacy policy on the
   Worker — all three land in the same `wrangler deploy`.
2. Foreground-service Monitor mode (or ship v1 honest + this as milestone 2).
3. Test matrix + CI harness; run the on-device matrix on the two phones.
4. Play Billing: the two products, the "Support Tawny" row, the "where your
   money goes" screen. Cosmetic perks only.
5. Store listing trust pass; trademark search; user does keystore.
6. iOS shell + StoreKit.

---

## Addendum — $2.99 paid-upfront vs a cheap subscription, and the donation path

The user asked to weigh **charging ~$3 at the door** against a **~$2/mo
subscription**, plus adding an **in-app "support development" donation** link.
Research summary; the recommendation still lands on *free + optional support*,
but paid-upfront is a defensible values choice and the reasoning is below.

### Is an in-app donation link legal?

**Google Play — yes, and outside Play Billing, if it unlocks nothing.** Play's
Payments policy has a peer-to-peer carve-out: if 100% of the contribution goes to
the developer **and it grants no digital content, service, or benefit of any
kind** (no badge, theme, ad-removal, nothing), Play Billing is *not* required and
you may send users to an external processor (Ko-fi, Open Collective, Stripe,
GitHub Sponsors, PayPal). The instant it unlocks anything — even cosmetic — it
becomes a digital purchase: Play Billing required, 15% cut, no linking out. So
the rule for Tawny: **a free "support development" link must buy literally
nothing.** (Alternatively, offer tip amounts *through* Play Billing as
consumables and accept the 15% for a smoother in-app flow — also allowed.)

**Apple — no.** Donations to a for-profit developer that isn't a registered
nonprofit must go through IAP (guideline 3.1.1 / 3.2.1); external donation links
get rejected. The 2025 US anti-steering ruling opens a narrow US-only external-
link crack, but for the iOS port assume a StoreKit tip jar and the commission.

### Best way to run the donation path (Android)

- Primary link: **Ko-fi** or **Buy Me a Coffee** — no giver account needed, warm
  low-pressure framing, ~0–5% fees, one-time and recurring. Right register for
  non-technical pet owners. (GitHub Sponsors / Liberapay are 0% but assume a
  FOSS-literate giver — wrong audience here.)
- Secondary link: an **Open Collective** or a plain "costs & funding" page for
  the transparency-minded — a public ledger of running costs vs. supporter
  income is the single biggest trust/conversion lever for donationware
  (Wikipedia / Blender pattern).
- Placement: a quiet permanent row on sessions-home and in an About screen; a
  one-line pointer *after a call succeeds* ("that worked — Tawny is free,
  [support it]"). **Never** on launch, mid-task, or after an error. It must never
  read as a paywall.
- Framing: name the specific cost ("the relay that lets you watch from outside
  your home costs bandwidth"), make it about keeping Tawny alive and free, anchor
  low ("$3 ≈ a month of relay for N households"), default the toggle to one-time,
  thank visibly, gate nothing — and say so ("you get nothing extra, that's the
  point").
- Mechanics: open the URL in a Custom Tab. Do **not** wire Play Billing to the
  external-link version. Add a short "where the money goes" note (relay, the $25
  Play fee, a domain, dev time) — specificity converts.

### $2.99 paid vs $2/mo subscription — how people actually perceive them

| Dimension | $2.99 once | $2 / month |
|---|---|---|
| **Ownership** | "It's mine." Endowment effect → higher perceived value, more tolerance for rough edges, ~nothing to churn from. | "I'm renting." No ownership feeling; every renewal re-opens "is this still worth it?" |
| **Pain of paying** | One sunk past event, quickly forgotten. | Recurring loss event; losses loom ~2× larger than gains, re-felt monthly. |
| **Mental ledger** | Adds nothing. "Handled." | Adds a line to the user's "things charging me monthly" list — the felt cost is *"another subscription,"* not *$2*. |
| **Usage-pattern fit** | Fine for intermittent use (trips, a new puppy, vet recovery). | Intermittent use is the #1 subscription-fatigue trigger — "I paid all year and used it twice." A pet monitor is maximally exposed to this. |
| **Price reading** | Left-digit bias anchors on "2"; reads as sub-$3, trivial, yes. | Read as "$X forever"; annualised ($24/yr) sounds steep for a phone app. |
| **Trust / motive** | "Charged me fairly and walked away." Matches the anti-dark-pattern brand. | Category-trained distrust ("free trial auto-charges," "hard to cancel," "price creeps"); a mandatory sub on a privacy app creates brand dissonance. *Optional* support at the same price inverts this — patronage, not rent. The framing does all the work. |
| **Control** | No exit action needed. | User must act (cancel) to stop it; inertia works against them and they resent it — the "loss of control" fatigue driver. |
| **Revenue reality (niche utility)** | Every install is revenue now; no cliff. | Higher LTV *only if they stay*; low-engagement consumer utilities churn ~60–80%/yr, so effective LTV (~$6–10) ends up near a one-time $2.99 with far more billing/support overhead and worse brand fit. |

For Tawny's profile — intermittent use, two-device setup friction, privacy-first
brand, solo passion project, small niche — **the cheap subscription is the worst
of the three options**: it fights the brand, the usage pattern, and the 2025
consumer mood (measurable ~6% shift toward one-time purchases; "ownership reduces
cognitive load").

### Is $2.99 paid-upfront viable?

**As a business: low.** Paid-upfront is <5% of store revenue and shrinking
(~72–85% is subscriptions). No try-before-buy means the install *is* the
purchase — you lose the entire top of funnel, plus review velocity, ranking
signal, and word of mouth. Google's 2-hour instant / 48-hour easy refunds are
**deducted from developer payout**, and this app is the worst case for the refund
window: a user *cannot* validate it solo — it needs two phones and often a second
person — so a meaningful share will install, fail to get a call working in two
hours, and refund. Expect hundreds to low-thousands of net sales over a year or
two.

**As a self-funding, values-expressing project: entirely viable.** It's the
cleanest statement of the ethos — one wall, at the door, then nothing: no
subscription, no ads, no billing SDK, no entitlement logic, no nagging. Research
shows a real segment *prefers* paid apps as a privacy signal ("free = data
mining"; ~6% of paid-app choosers cite this) — and a privacy-first pet camera is
exactly where "we charge $3 so we never need your data" is coherent. Cash costs
are near-zero (Cloudflare free tier; coturn on Oracle free = $0; one-time $25
Play fee), so a few hundred sales cover them.

### Recommendation

Ranked:

1. **Free, full-function, no ads/account + an Android external donation link**
   (Ko-fi one-time/recurring, unlocks nothing → policy-clean, ~0–3% fees) +
   Open Collective for transparency. Keeps the whole discovery funnel and word
   of mouth; the people who value it fund it; the brand stays pure. iOS later
   gets a StoreKit tip jar because Apple forces it.
2. **$2.99 flat, permanent, no launch-discount games** — if the user wants money
   at the door for its signal value and simplicity, and accepts a large hit to
   reach and a refund tax from the two-phone requirement. Still add the same
   external "support further development" link for people who want to give more.
3. **A mandatory ~$2/mo subscription — don't.** Wrong for this product on every
   axis above. A *purely optional* "Supporter" sub at that price is fine, but at
   that point it's just the donation path with a Play Billing wrapper and a 15%
   cut, so prefer option 1's external link.

### Where the donation link goes in the app (decided)

Ko-fi page: **`https://ko-fi.com/tawnyone`** (goal: "Keep the relay running",
$15/mo). Build it as:

- **Primary:** one quiet `link()` row at the bottom of `showSessionsHome()` —
  *"Support Tawny · help pay for the relay"*. Never on welcome/role/pairing —
  it must never sit in the setup funnel or read as a paywall.
- **Secondary:** a small **About** screen (the app has none today — only the
  hidden diag hatch). Reachable from a sessions-home overflow / footer link.
  Holds: one-line "what Tawny is", the privacy one-liner, **Support development →
  ko-fi.com/tawnyone**, the support email. Also the place a cautious user
  verifies the link is genuinely ours.
- **Optional:** at most one dismissible pointer in `afterSession()` / Bridge
  `"ended"`, rate-limited to ~once every few weeks. Skip if unsure.
- **Mechanism:** plain `Intent(ACTION_VIEW, Uri.parse(...))` to the system
  browser — no `androidx.browser` dependency, and it keeps the URL out of the
  first-party WebView. Not a Custom Tab (not worth the weight).
- **Web client:** same link in the existing `<footer class="fine">` on the
  channels screen.
- **Keep it policy-clean:** external link only, **no Play Billing**, and
  supporters get **nothing** in the app — that is what keeps it outside Play's
  cut (P2P tip-jar carve-out). Mirror the URL in the README and the hosted
  privacy policy so it is cross-checkable.
