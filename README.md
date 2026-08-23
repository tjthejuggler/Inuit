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
   Stats, knowledge summaries and Socratic follow-up threads are **frozen for
   the whole session** and only absorb new answers when the user comes back to
   the app (activity resume) — never in real time while answering.
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
   knowledge-space growth over time.

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
