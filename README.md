# Inuit — an intuition trainer

> *"Inuit" — from Latin *intueri*: to gaze upon, to know by direct perception.*

Inuit builds the user's intuition by asking short questions drawn from every area of
human knowledge. The app **never reveals answers**. When the user doesn't know
something, that fact is recorded and used — later, gently, without urgency — to
generate *simpler questions that lead toward* the unknown, Socratic style. The
user's growing knowledge space is mapped live on the stats screen, which is the
heart of the app.

---

## Core principles (do not violate these when evolving the codebase)

1. **Never reveal answers — and never reveal correctness.** Not after a wrong
   answer, not in stats, not in hints, not in logs rendered to the user. The
   correct answer is stored (needed for grading and for the generator), but no
   UI path may ever display it. The user must not know whether any specific
   answer was right or wrong: feedback is a neutral acknowledgment only.
   Stats and Socratic follow-up threads are **frozen for the whole session**
   and only absorb new answers when the user comes back to the app (activity
   resume) — never in real time while answering. The LLM-written rolling
   knowledge summaries can describe which answers were right or wrong, so
   they are internal generation context only and must never be rendered in
   any UI.
2. **Socratic decomposition.** A wrong answer does not trigger an explanation. It
   creates an *unknown* in the knowledge model. Later batches include simpler,
   independently verifiable sub-questions (linked via `parentId` / `rootId`)
   whose answers let the user eventually *derive* the parent answer themselves.
   Sub-questions must never simply restate the parent question with the answer
   embedded.
3. **No hallucinated questions.** The generator must only emit questions whose
   answers are objectively verifiable and that the model is highly confident in
   (self-reported `confidence`, threshold configurable, default 0.8). An optional
   second-pass **verifier** call re-checks each batch and drops flagged items.
   Obscure statistics questions may be grounded via MCP web tools — but with a
   strict per-batch tool budget (default 3 calls) so generation stays cheap.
4. **Variety.** Types: true/false, multiple choice, numeric (with tolerance and
   unit hints), fill-in-the-blank. Domains: anything and everything — trivia to
   obscure statistics — always introducing new realms, not just drilling knowns.
5. **The map matters.** Every question is tagged with hierarchical domain paths
   (`"Science > Physics > Optics"`). The stats screen aggregates these into a
   living map of the user's knowledge: radar of top-level realms, proficiency
   bars, weakest/strongest areas, an expandable domain tree that *grows in
   complexity* as more questions are answered, activity and accuracy trends, and
   knowledge-space growth over time. The knowledge map card opens a
   full-screen, landscape-locked **interactive knowledge map**: the entire
   realm taxonomy laid out as an explorable galaxy (sections spiral outward,
   realms cluster around them, territories orbit each realm), pan/zoom/tap
   with a drill-down side panel, frozen proficiency overlaid, charted /
   uncharted filtering.

## How it works

```
┌────────────┐    answers     ┌──────────────┐   stratified context   ┌─────────────┐
│  Question   │ ───────────▶ │  QuestionStore │ ─────────────────────▶ │   Context    │
│  queue      │ ◀─────────── │  (JSON file)   │                        │   Builder    │
└────────────┘  new batch    └──────────────┘                        └──────┬──────┘
      ▲                                                                  │
      │          refill when queue < threshold (default 150)             ▼
      │                     ┌────────────────┐   tools (budgeted)  ┌──────────────┐
      └──────────────────── │  Generator      │ ◀────────────────── │  LLM client   │
                            │  + Verifier     │                     │  + MCP client │
                            └────────────────┘                     └──────────────┘
```

- **Queue**: unserved questions. After every answer, if the queue is low the
  generator runs in the background. Question selection (`data/QuestionSelector.kt`)
  leaps across the knowledge space (avoids the top-level realms of the last few
  questions), interleaves sub-questions of lineages missed in *previous*
  sessions only (so a follow-up can never betray how the question just answered
  went; choice-format sub-questions are preferred — recognition scaffolds
  recall), and **spaced revisits**: previously answered questions return with
  ~20% probability — wrong answers weighted 4× over correct ones, never within
  6 answers of their last serving, never the question on screen. The
  wrong/correct mix is what keeps a re-ask from revealing correctness. When the
  queue runs dry mid-session, a revisit fills the gap while a batch generates.
- **Context budgeting** (keeps LLM calls small as history grows):
  - last ~40 answers verbatim,
  - a *sampled* set of unknown lineages (recent + random older, with their
    existing sub-question chains pulled in — "related questions from the past"),
  - a sample of correct answers for calibration,
  - a compact domain proficiency digest (weakest / strongest / most active),
  - **rolling knowledge summaries**: every N answers the LLM condenses each
    top-level domain's history into a ≤120-word state summary that replaces raw
    old history in future contexts,
  - **serendipity frontiers** (see below): distant exploration targets plus
    deliberate revisits.
- **MCP**: the user pastes an MCP servers JSON (streamable-http servers with
  `url` + `headers`). Seeded default: z.ai `web-search-prime` and `web-reader`
  (API key to be filled in by the user). Only these remote HTTP servers are
  supported on-device; stdio servers in the JSON are ignored.
- **LLM**: any OpenAI-compatible `/chat/completions` endpoint (base URL, API
  key, model, temperature configurable in Settings).
- **Serendipity engine** (`data/gen/Serendipity.kt` + `RealmTaxonomy.kt`),
  inspired by the Serendipity Engine project: a wide curated taxonomy of ~50
  realms and 250+ subrealms (from Physics to Vexillology) stands in for a
  vector topic database. Recent answers build a time-decayed familiarity
  profile (half-life ≈ 2 days) over domain paths; candidates are scored by
  lexical distance from that profile (shared top realm ≫ shared subrealm ≫
  word overlap). Each generation context receives 8 **distant frontiers**
  (maximally unlike anything recent — obscure fields welcome, one per realm)
  and up to 4 **revisits** (old, weak threads that have aged past a 3-day
  freshness shadow) — consistently asking far-away questions while
  occasionally circling back.

## Project layout

```
app/src/main/java/com/example/inuit/
  InuitApp.kt, AppGraph.kt, MainActivity.kt, BatchGenService.kt
               (foreground service: batches survive app close / screen off)
  data/        Model.kt QuestionStore.kt SettingsStore.kt Grader.kt StatsCalculator.kt
               QuestionSelector.kt   (pick strategy: threads, spaced revisits, diversity)
               PodcastApps.kt        (podcast app discovery + episode opening)
  data/llm/    Http.kt LlmClient.kt McpClient.kt
  data/gen/    Prompts.kt ContextBuilder.kt QuestionGenerator.kt Validator.kt
               Serendipity.kt RealmTaxonomy.kt   (distant-frontier planner)
               AdaptiveSignals.kt  (off-category answers → novice-domain scaffolding)
               McpSession.kt Harvester.kt  (bulk web trivia stockpiling)
               PodcastRecommender.kt  (weak-area-targeted episode picks)
  ui/          MainScreen.kt QuestionCard.kt StatsSections.kt SettingsScreen.kt MainViewModel.kt
               PodcastCard.kt      (bottom-of-stats episode prescription)
  ui/charts/   Charts.kt        (custom Canvas charts — no chart dependency)
  ui/theme/                    (dark-first palette)
```

Persistence is a single JSON file (`files/inuit_store.json`, atomic writes) plus
DataStore preferences for settings — deliberately dependency-light (no Room/KSP).

## Roadmap / ideas for future models & tools

- Embedding-based retrieval over past questions instead of (or blended with)
  stratified random sampling for context assembly.
- Item-Response-Theory difficulty calibration per domain (replace LLM-guessed
  difficulty with empirically fitted curves as data accumulates).
- True spaced-repetition scheduling (SM-2-style per-question intervals) beyond
  the current lightweight wrong-weighted revisit heuristic.
- Per-domain summary quality checks; hierarchical summaries at depth 2+.
- Provider-native structured outputs / JSON schema enforcement when available.
- Export/import of the knowledge store; multi-profile support.
- On-device small-model grading of fuzzy fill-in-the-blank answers.

## Changelog

- **2026-08-23 (16)** — **Refill engine: batches file into the net they
  were generated for, every net is kept stocked in the background, and
  failed batches always come back.** Audit findings that made batches slow
  and fragile: (1) switching nets mid-batch DISCARDED the in-flight batch
  (minutes of LLM work thrown away; the left net stayed empty until the
  user returned to it) — batches now file into the net they were generated
  for via net-scoped store writes (`insertQuestionsFor` etc.), regardless
  of what net is active; only a deleted net discards. Mid-batch reads were
  also active-net-scoped: the validator deduped against the wrong net's
  questions and rolling summaries could be filed into the wrong net after a
  switch — context building, dedup, frontiers and summaries are all
  target-net-scoped now (`ContextBuilder.build(net, netId)`). (2) Only the
  ACTIVE net was ever refilled — the refill loop now keeps EVERY net at the
  threshold: active net first (`pickNeedyNet`, unit-tested), then the
  emptiest other net in the background, so a new net starts filling even
  while the user trains elsewhere, and drained nets recover before the user
  switches back. Long harvests yield between rounds when the active net
  becomes needy. (3) Non-network errors (validation discard, API errors)
  stalled generation forever — an empty queue has no refill trigger (no
  answers → no maybeGenerate), so a single bad batch could kill refills
  until an app restart. ANY failed episode now schedules a comeback with
  the escalating back-off (1 min → ×5 → 15 min cap, reset on success). (4)
  The foreground service stopped during retry back-off and between
  batch/harvest phases, letting Android reap the process and silently
  cancel the pending retry — the service now lives for the entire refill
  run AND through retry waits (`QuestionGenerator.serviceNeeded`, held by
  ref-counted jobs; the wake lock is refreshed every minute so multi-net
  refills outlive its 10-minute safety expiry). Leaving the app or turning
  the screen off never interrupts a batch or a scheduled retry; START_STICKY
  still resumes after a process kill. (5) Latency: the adaptive
  thinking-off mode is now sticky for a whole refill RUN instead of reset
  per batch (a slow reasoner no longer re-pays a 90 s+ thinking call on
  every chained batch of a multi-batch refill), and one MCP session is
  shared across a net's batch + harvest rounds instead of re-handshaking
  every server per call. (6) `QuestionStore.switchNet` could clobber a
  just-filed batch with a stale pre-switch snapshot on disk — the flush now
  re-snapshots under the lock at write time. Progress notes are tagged with
  the net's name when a background net is being filled. 7 new unit tests
  (`NetSchedulingTest`: scheduler priority + retry cadence).

- **2026-08-23 (15)** — **Net stats drop the redundant "Net >" prefix on
  screen, and the generator now escalates difficulty (Socratic boundary).**
  Two follow-ups to (13)/(14). Display: inside a custom net every category
  label repeated the net name ("Juggling > Notation") — pointless, since
  everything on screen is already that net. Stored tags stay net-rooted
  (grouping, frontier planning and validation depend on the prefix); the
  stripping happens at every presentation boundary: `StatsCalculator.topKey`
  now returns the bare subtopic ("Notation"), `buildTree` drops the net
  root (so the domain tree and the knowledge map start at the subtopics),
  `Snapshot` carries `netName`, and `QuestionCard` takes a `netName` param
  to trim its domain tag. The knowledge map inside a custom net is now the
  net's own landscape: one section named after the net, one realm per
  subtopic — the all-knowledge taxonomy is out of scope there (a juggling
  subtopic called "History" no longer leaks into the generic History
  realm). Adaptive challenge: new rule 9 in the system prompt ("ADAPTIVE
  CHALLENGE" — recent performance is a difficulty dial; sustained correct
  answers must raise difficulty until misses appear, misses ease back; aim
  at the boundary of the user's knowledge), a CHALLENGE ESCALATION section
  in the generation request (`AdaptiveSignals.challengeDomains`: ≥80% over
  ≥3 attempts, aggregated at subtopic level inside nets, realm level in
  All), and a strengthened TASK directive — all phrased generically for
  every net, All included. Tests: StatsNetAggregationTest rewritten for
  prefix-free keys + tree, KnowledgeLandscapeTest net section,
  AdaptiveSignalsTest challenge domains, new PromptsTest. 115 passing.

- **2026-08-23 (14)** — **Stats knowledge map now aggregates custom nets at
  the subtopic level (retroactive).** Follow-up to (13): inspecting the
  on-device net stores showed the questions were NEVER flat-tagged — the
  Juggling net's 30 answered questions already carried rich paths
  ("Juggling > Notation > Siteswap", "Juggling > History > Ancient Art",
  …), and AI Agents likewise. What collapsed them was
  `StatsCalculator.compute`'s top-level aggregation, which keys on the
  FIRST path segment — inside a net that is always the net name, so the
  stats-screen knowledge map (radar + realm rows, driven by
  `topDomains`) rendered a single "Juggling" spoke and the Realms chip
  read 1. Fix: `compute` gained a `netName` parameter (passed by
  `MainViewModel.computeSnapshot` for the active custom net) and a
  `topKey` helper — net-rooted paths aggregate at the first TWO segments
  ("Juggling > Notation"), everything else keeps the legacy first-segment
  behavior; `domainsExplored` and the knowledge-space growth chart use
  the same key. No data migration was needed: all existing answers light
  up as subtopic categories immediately (Juggling → Notation / Patterns /
  History / Props / Records / Organizations…, AI Agents → Protocols /
  Frameworks / Benchmarks / Methods…). The empty Turkish net needs
  nothing — generation now tags hierarchically from the start.

- **2026-08-23 (13)** — **Fixed custom nets charting as a single flat point
  on the knowledge map.** Symptom: 30 juggling answers spanning siteswap,
  patterns, history and famous jugglers, yet the map showed one realm
  ("Juggling") with one point. Root cause chain: the generator prompt let the
  model tag every net question with the bare net name (and told it to *reuse*
  existing context paths, freezing the first flat tag forever); the Validator
  accepted single-segment domains; `Serendipity`/`ContextBuilder` fed custom
  nets all-knowledge taxonomy frontiers (out of scope) and deduped frontiers
  by top-level realm — which collapses every "Net > Subtopic" path to one
  inside a net; `QuestionSelector`'s diversity key used only the top segment,
  which is identical for every question in a net. Fixes: (1) `Prompts` now
  carries an explicit NET DOMAIN TAGGING contract — every path must be
  "Net > Subtopic" (flat net-name tags are invalid), batches must spread
  across many distinct subtopics, and `new_frontiers` must be net-scoped;
  the harvester prompt enforces the same for stockpiled trivia. (2)
  `Validator.parseAndValidate` gained a `net` parameter and
  `normalizeNetDomains`: bare subtopics are prefixed ("Siteswap" →
  "Juggling > Siteswap"), prepended all-knowledge realms are stripped
  ("Sports & Games > Juggling > Props" → "Juggling > Props"), and questions
  left with only the flat net name are dropped with a diagnostic reason.
  (3) `Serendipity.planFrontiers` gained a net mode (`netName`) — candidates
  are net-rooted LLM frontiers plus queued-but-unanswered subtopics, with
  diversity enforced at the SUBTOPIC level for both distant frontiers and
  revisits; `ContextBuilder` passes the active net so taxonomy paths can no
  longer leak into a net's frontier plan. (4) `QuestionSelector.diversityKey`
  keys on the first TWO path segments, so consecutive questions roam the
  net's different subtopics. Existing flat-tagged history is untouched (it
  still charts under the net realm); new batches fill out the territories.

- **2026-08-23 (12)** — **Fixed "generating forever": streaming LLM calls +
  adaptive thinking-off + bounded retries.** Diagnosis from the on-device
  debug log: reasoning models (GLM-5 on z.ai) regularly think for 5+ minutes
  before the FIRST byte of a non-streaming chat response, so the client's
  300 s read timeout fired on perfectly healthy calls, was misclassified as
  a transient network error, and the 60 s auto-retry relaunched the same
  doomed ~16-minute cycle forever — the UI showed "Generating…" for an hour+
  (new nets were hit hardest; the podcast recommender was unaffected because
  its calls are light). Fixes: (1) `LlmClient` now requests `stream: true`
  and assembles SSE deltas (content, `reasoning_content`, fragmented
  tool_calls, usage) via `ChatStreamAccumulator` — tokens trickle out while
  the model thinks, so the HTTP timeout only fires on genuinely stalled
  streams; servers that ignore/reject streaming fall back to plain-JSON
  parsing transparently. (2) `QuestionGenerator.chatAdaptive` — if a call
  with deep thinking enabled is slow (>90 s) or times out, the rest of the
  batch (verifier, summaries, harvest) is sent with thinking disabled
  automatically (the user's global setting is untouched); batches drop from
  many minutes to ~1-2. (3) Hard 20-minute wall-clock ceiling per batch
  (`BATCH_DEADLINE_MS`) — the UI can never sit in "Generating…" indefinitely
  again. (4) The post-failure auto-retry now escalates 1 min → 5 min → 15 min
  (reset on success) instead of hammering every 60 s. Regression tests in
  `LlmStreamTest` (SSE assembly, tool-call fragment merging, streamed errors,
  usage, backoff caps).
- **2026-08-23 (11)** — **Interactive knowledge map + summaries sealed.** The
  "Knowledge state" card (rolling LLM summaries) is gone from the stats
  panel — those summaries can tell the user which questions they got right
  or wrong, so they are now strictly internal generation context (the
  ViewModel no longer even exposes them to the UI). The knowledge map card
  is now tappable ("Explore the full landscape ›") and opens a full-screen,
  **landscape-forced interactive knowledge map** — the stats radar evolved
  into an explorable galaxy of the *entire* realm taxonomy: sections spiral
  outward on a golden-angle sunflower (deterministic, evenly spaced), realms
  cluster around their section (growing with answer volume, colored by
  frozen proficiency), and every territory (subgroup) orbits its realm as a
  dot; uncharted land stays dim, LLM frontier paths get their own "Frontiers"
  cluster. Interactions: one-finger pan, pinch zoom, double-tap zoom-in,
  +/−/fit buttons, and tap-to-inspect any node — a drill-down side panel
  shows section → realm → territory detail (rings, proficiency bars,
  charted/uncharted territories) with tappable rows and breadcrumbs.
  Decluttered by design: the map OPENS zoomed in on the user's
  most-answered charted realm (never a wall of text), and labels follow
  strict level-of-detail rules with screen-space collision avoidance —
  section names appear from zoom 0.30, realm names from 0.75, territory
  names from 1.8, and any label that would overlap an already-placed one
  simply isn't drawn (the selected node's label always wins space first),
  so zooming out makes text disappear instead of piling up. ⤢ is the
  explicit fit-all overview.
  Charted/Uncharted filter chips re-shape the map; a legend explains the
  color coding. The screen locks to sensor-landscape while open and restores
  the previous orientation on exit (activity handles orientation
  configChanges itself, so the lock never recreates it). Layout and merge
  logic are pure Kotlin in `ui/KnowledgeLandscape.kt`, unit-tested.
  90 unit tests (4 new).
- **2026-08-23 (10)** — **Occasional accents (location / date / cross-net).**
  Each net — including the All net, which gained an edit button in
  Settings → Nets (its dialog hides the fixed name/scope and offers
  podcasts + accents only) — can now switch on three optional "seasonings"
  from its edit dialog: **Location accents** (questions tied to the
  phone's current region — coarse location + reverse geocoding, permission
  requested when the toggle flips on, graceful fallback to rounded
  coordinates and silent skip when unavailable/stale >30 days), **Date
  accents** (today's weekday/day-of-year, an on-this-day-in-history angle,
  same-year-in-past-centuries anniversaries, and a hemisphere-correct
  season line that reuses the location fix), and **Pull knowledge from
  other nets** (user-picked source nets, max 3: their rolling knowledge
  summaries, weakest domains and recently missed question prompts season
  the current net's questions — a bridge, never a departure, since accent
  questions must still fit the net's scope). Strict dosage by design: a
  dedicated "OCCASIONAL ACCENTS" prompt block allows at most ONE question
  per accent and hard-caps accent questions at ⌈batch/6⌉ ≤ 3, with an
  explicit "skip if it doesn't fit the net's scope" rule; the net's own
  material always dominates. Deleting a net prunes it from other nets'
  source lists; `updateNet` validates source ids against the registry;
  legacy `inuit_nets.json` entries parse with accents off. Multiple-choice
  option text is now left-aligned (it fills the button width instead of
  floating centered). 86 unit tests (10 new: date/anniversary/season
  rendering, cross-net line assembly, missed-prompt extraction, dosage
  cap, net JSON round-trips).
- **2026-08-23 (9)** — **Nets.** Every feature now lives inside a *net* — a
  scoped question universe. The built-in **All** net is the app exactly as
  it was (all pre-nets data migrated into it for free: it keeps the legacy
  `inuit_store.json`), and users can create any number of custom nets
  (e.g. *Juggling — patterns, science, technology, history, research*) from
  Settings → Nets. A new net starts completely empty — questions, answers,
  stats, knowledge map, frontiers, podcast recs are all per-net with zero
  carryover — while LLM/MCP/generation settings stay global. Switching nets
  happens in a dropdown where the old "N queued" chip sat in the top bar;
  generation, harvesting, summaries and podcast picks all inject the net's
  scope description into their prompts (custom nets also skip the
  all-knowledge taxonomy frontiers and harvest net-scoped trivia), and
  batches in flight when the user switches nets are discarded rather than
  filed into the wrong net. Podcast recommendations can be toggled off per
  net. Internally: `NetStore` (registry, `inuit_nets.json`) +
  `QuestionStore` holding one lazily-loaded `NetState` per net
  (`inuit_store_<netId>.json` per net), every store method operating on
  the active net.
- **2026-08-23 (8)** — **Podcast stockpile.** The recommender now keeps a
  queue of up to 3 fully-resolved episodes (LLM pick + iTunes feed/episode
  grounding) behind the one on screen, persisted in the store
  (`podcastQ`). Tapping the card promotes the next stockpiled episode to
  the card INSTANTLY — no waiting for a fresh LLM + directory pass — and
  the stockpile refills in the background (stale or duplicate picks are
  dropped at promotion/enqueue time; the avoid-list now covers current +
  queued + seen shows so spares don't echo what's lined up).
- **2026-08-23 (7)** — **Clipboard staging + softer variety rule.** When the
  feed-subscribe link opens the show in the chosen podcast app (Pocket Casts
  has no episode deep link), the episode title is now staged on the
  clipboard with a toast — one paste into the app's search jumps to the
  episode (Android cannot paste into other apps' UIs, so clipboard-write is
  the practical ceiling short of an accessibility service). The show-variety
  rule was softened: appropriateness to the user's gap is the guiding
  principle; given comparable candidates the model prefers unexplored
  shows, but a better-fitting episode from a recent show wins over a weaker
  new one.
- **2026-08-23 (6)** — **Episode-level links, show diversity, collapsible
  reasons.** Pocket Casts exposes no episode-level deep link (its `pktc://`
  scheme covers open/play/pause/subscribe only — episode links are an open
  feature request), so the subscribe sheet remains the deepest in-app
  landing; recommendations now also resolve the exact EPISODE through the
  iTunes directory (`entity=podcastEpisode`, filtered to the queried show)
  and store its Apple episode page as the rec's URL — the system/browser
  path opens the specific episode, and the clipboard fallback already
  carries "show + episode" for instant in-app search. The prompt now
  forbids repeating an already-suggested SHOW (recent distinct shows are
  listed as off-limits), ending the same-show-every-time loop. Descriptions
  are collapsed by default: the card's reason clips to two lines with a
  more/less toggle, and the history dialog expands one row's reason at a
  time (tap a row to expand, ▶ to open). 70 unit tests (2 new).
- **2026-08-23 (5)** — **Podcast deep links that land in the app.** There is
  no universal podcast deep link, but the RSS feed URL is a universal
  *identifier* every podcast app understands. Recommendations are now
  grounded at runtime against Apple's keyless iTunes Search API (new
  `PodcastDirectory`): the show's real `feedUrl` — plus its Apple Podcasts
  page when the model produced no link — is resolved at generation and
  persisted on the rec. Apps with documented feed-subscribe schemes now open
  the exact show directly: Pocket Casts `pktc://subscribe/<feed>` and
  AntennaPod `antennapod-subscribe://<feed>` (feed URL without its own
  scheme, per Pocket Casts' URL-scheme documentation). This replaces the
  invalid `pktc://search/` guess that Pocket Casts' catch-all handler
  rejected with "not a valid podcast share link"; undocumented scheme
  guesses (Podcast Addict search) were removed for the same reason.
  Remaining chain: episode page restricted to the app → documented search
  links (Spotify, YouTube Music) → clipboard + cold launch. Tapping resolves
  the feed on demand in the ViewModel, so recs persisted before this change
  (and history entries) open correctly too. 68 unit tests (5 new).
- **2026-08-23 (4)** — **Podcast prescriptions, hardened.** Episode links are
  now required in practice: the prompt demands a stable public episode page
  (Apple Podcasts preferred), the parser only accepts absolute http(s) URLs
  with a real host, and when MCP web tools are configured the recommender
  runs a small tool loop (≤2 calls) so the model can search for/verify the
  episode page; a missing URL triggers one corrective retry. The card gained
  a history button opening a dialog of previously clicked episodes (each
  tappable, opening in the podcast app), and when no podcast app is chosen
  it shows a "Choose your podcast app in Settings ›" link that navigates
  straight to the Settings picker. Opening is now guaranteed to land in the
  chosen podcast app, never the browser: episode URL restricted to the app →
  the app's own search deep link (best-effort registry: Spotify, Podcast
  Addict, Pocket Casts, YouTube Music) → cold-launch the app with the search
  query copied to the clipboard (toast prompt). Linkless recommendations
  regenerate after 10 minutes instead of 24 h. 63 unit tests (1 new).
- **2026-08-23 (3)** — **Podcast prescriptions + honest streak.** The stats
  panel now ends with a "Podcast prescription": one real, LLM-chosen episode
  targeting the user's weakest knowledge areas (lowest-accuracy domains,
  recent misses and rolling summaries feed the prompt; famous evergreen
  episodes of well-known shows only — the same anti-hallucination stance as
  the question engine, a direct URL only when the model is certain of a
  stable public page). Tapping the card opens the episode in the user's
  podcast app — selectable in Settings → Podcasts (default: system
  resolution, with layered fallbacks: chosen app → system handler → web
  search) — and immediately retires the pick; the next episode generates in
  the background, and clicked episodes are remembered and avoided. The
  overview metrics were reworked: the confusing correct-answer
  "Streak"/"Best" pair is gone — "Day streak" now counts consecutive days
  of usage (anchored today-or-yesterday, correctness irrelevant) — and the
  horizontally-scrolling chips became a compact fixed 3×2 grid that is
  always fully visible. 62 unit tests (9 new).
- **2026-08-23 (2)** — **Tail habit integration.** Inuit now reports the number
  of questions answered to the Tail habit tracker (same IPC protocol as WAGS:
  explicit permission-guarded broadcasts + habits ContentProvider, signature
  permission `com.example.tail.permission.TAIL_INTEGRATION`). A single
  count-based habit slot ("Questions answered") is configurable in Settings →
  Tail app via a searchable picker; every submitted answer fires a +1
  increment. Retroactive backfill (`ACTION_SET_HABIT_VALUES`, idempotent SET
  semantics) pushes per-day counts for the entire answer history — everything
  from the past plus today so far — automatically when a habit is connected,
  and manually via "Backfill answer history". Aggregation buckets answers by
  local calendar date, unit-tested. 44 unit tests (3 new).
- **2026-08-23** — **Resilient batches + adaptive difficulty + web stockpile.**
  Batch generation now runs under a foreground service (`BatchGenService`:
  dataSync type, partial wake lock, START_STICKY) — closing the app or turning
  the screen off no longer kills an in-flight batch, and if the system still
  kills the process the service restarts and generation resumes from the
  persisted store (batches, answers and the on-screen question are written
  immediately, not debounce-delayed). Adaptive difficulty: wrong free-text
  answers now carry the user's raw answer into the generation context;
  off-category answers ("Africa" for the largest planet) are flagged by a
  local heuristic (`Grader.isWildMiss`) and by the LLM itself (new prompt
  rule), marking the domain NOVICE — future batches scaffold it with
  difficulty 1-2 multiple-choice / true-false questions that introduce the
  domain's basic entities before recall questions return. No wasted
  questions: the on-screen question id is persisted and restored on app
  restart. Stockpile: when a personalized batch leaves the queue below the
  (new, much larger — default 150) threshold, the new `Harvester` searches
  the web via MCP tools for large trivia lists, converts, tags, validates and
  fact-checks them through the same pipeline (toggleable in Settings).
  41 unit tests (13 new).
- **2026-08-22 (4)** — **Instant flow + spaced revisits.** Submitting an answer
  now advances to the next question immediately — the neutral acknowledgment
  panel and "Next question" button are gone (a stale double-tap on the old
  answer button is ignored via a question-id guard). New pure selection
  strategy (`data/QuestionSelector.kt`, unit-tested): Socratic threads surface
  at 50% when available (still gated to prior-session misses; multiple-choice /
  true-false sub-questions preferred per the new generator prompt rule), and
  previously answered questions come back as spaced revisits — wrong answers
  weighted 4×, correct ones 1×, minimum gap of 6 answers, current question
  excluded, empty-queue fallback so the user is never idle mid-generation.
  27 unit tests (7 new).
- **2026-08-22 (3)** — **Blind training + serendipity engine + UI refresh.**
  The user must never know whether specific answers were right or wrong:
  the correct/incorrect banner, streak flames and the collapsed-card verdict
  dot are gone — submitting now shows a neutral "answer woven in"
  acknowledgment. Stats and knowledge summaries are session-frozen
  (recomputed only on activity resume) so nothing updates in real time while
  answering; Socratic follow-up threads are gated to lineages missed in
  *previous* sessions. New serendipity planner (wide realm taxonomy +
  time-decayed familiarity profile + lexical distance) feeds 8 distant
  frontiers and up to 4 revisits into every generation context, and question
  selection now leaps across top-level realms. Visual refresh: deeper palette,
  gradient accents, lettered choice buttons, hairline-bordered cards,
  uppercase metric tiles, theme-driven charts. 20 unit tests (6 new for the
  serendipity planner).
- **2026-08-22 (2)** — **Diagnostics infrastructure + thinking-model fix.**
  First real-device generation failed opaquely; root-caused by reproducing the
  exact request: reasoning models (glm-5) spend tokens on internal reasoning
  BEFORE content — a 6k max_tokens budget was exhausted by reasoning alone
  (finish_reason "length", empty content). Fixes: generation budget raised to
  16k with automatic double-and-retry on empty content; "Disable deep thinking"
  toggle in Settings (GLM `thinking: disabled`); transient network errors
  (flaky mobile DNS) retry with backoff (3 attempts, 5s/15s) plus a scheduled
  retry a minute later. Diagnostics: every LLM request/response (latency,
  finish_reason, token usage), MCP handshake/tool call, validation drop reason,
  verifier flag and error is logged to a persistent ring buffer — visible in
  Settings → Diagnostics, logcat (tag `Inuit`), or `files/inuit_debug.log`
  (survives restarts; pull via
  `adb shell run-as com.example.inuit cat files/inuit_debug.log`).
  Verified end-to-end on device: 30 questions generated, 1 dropped by the
  verifier, +29 queued.
- **2026-08-22** — Initial implementation: question engine (4 types, Socratic
  sub-question lineage, anti-hallucination verifier, MCP web tools with budget),
  queue with threshold refill, rolling summaries, full stats screen (radar,
  proficiency bars, domain tree, trends, growth), settings (LLM + MCP JSON +
  knobs), seeded z.ai web-search/web-reader MCP config.
