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

1. **Never reveal answers.** Not after a wrong answer, not in stats, not in hints,
   not in logs rendered to the user. The correct answer is stored (needed for
   grading and for the generator), but no UI path may ever display it. Feedback is
   limited to "correct / not quite" plus streaks.
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
      │          refill when queue < threshold (default 50)              ▼
      │                     ┌────────────────┐   tools (budgeted)  ┌──────────────┐
      └──────────────────── │  Generator      │ ◀────────────────── │  LLM client   │
                            │  + Verifier     │                     │  + MCP client │
                            └────────────────┘                     └──────────────┘
```

- **Queue**: unserved questions. After every answer, if the queue is low the
  generator runs in the background. Question selection interleaves fresh domains,
  sub-questions of recently-missed roots, and spaced re-approaches.
- **Context budgeting** (keeps LLM calls small as history grows):
  - last ~40 answers verbatim,
  - a *sampled* set of unknown lineages (recent + random older, with their
    existing sub-question chains pulled in — "related questions from the past"),
  - a sample of correct answers for calibration,
  - a compact domain proficiency digest (weakest / strongest / most active),
  - **rolling knowledge summaries**: every N answers the LLM condenses each
    top-level domain's history into a ≤120-word state summary that replaces raw
    old history in future contexts,
  - a frontier list (rarely/never touched domains) to force exploration.
- **MCP**: the user pastes an MCP servers JSON (streamable-http servers with
  `url` + `headers`). Seeded default: z.ai `web-search-prime` and `web-reader`
  (API key to be filled in by the user). Only these remote HTTP servers are
  supported on-device; stdio servers in the JSON are ignored.
- **LLM**: any OpenAI-compatible `/chat/completions` endpoint (base URL, API
  key, model, temperature configurable in Settings).

## Project layout

```
app/src/main/java/com/example/inuit/
  InuitApp.kt, AppGraph.kt, MainActivity.kt
  data/        Model.kt QuestionStore.kt SettingsStore.kt Grader.kt StatsCalculator.kt
  data/llm/    Http.kt LlmClient.kt McpClient.kt
  data/gen/    Prompts.kt ContextBuilder.kt QuestionGenerator.kt Validator.kt
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
- True spaced-repetition scheduling for re-approaching unknown roots.
- Per-domain summary quality checks; hierarchical summaries at depth 2+.
- Provider-native structured outputs / JSON schema enforcement when available.
- Export/import of the knowledge store; multi-profile support.
- On-device small-model grading of fuzzy fill-in-the-blank answers.

## Changelog

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
