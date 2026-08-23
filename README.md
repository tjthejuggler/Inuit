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
  data/llm/    Http.kt LlmClient.kt McpClient.kt
  data/gen/    Prompts.kt ContextBuilder.kt QuestionGenerator.kt Validator.kt
               Serendipity.kt RealmTaxonomy.kt   (distant-frontier planner)
               AdaptiveSignals.kt  (off-category answers → novice-domain scaffolding)
               McpSession.kt Harvester.kt  (bulk web trivia stockpiling)
  ui/          MainScreen.kt QuestionCard.kt StatsSections.kt SettingsScreen.kt MainViewModel.kt
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
