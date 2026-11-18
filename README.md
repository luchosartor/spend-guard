# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it's ingested — simulating a card-processor webhook — by a hybrid pipeline that combines deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

## Running locally

Everything runs in Docker — the only requirement is Docker itself.

```bash
docker compose up --build   # Postgres on :5433, API on :8080
```

If you have an Anthropic API key and want the AI reviewer to actually call Claude, put it in a `.env` file next to `docker-compose.yml` (`ANTHROPIC_API_KEY=...`, gitignored, never committed) before running the command above — `docker compose` picks it up automatically. Without a key, detection still runs end-to-end on rules alone (the AI reviewer reports "unavailable" and the service falls back to rule-only scoring, by design).

If `5433` or `8080` are already taken on your machine, override them in the same `.env` file (`DB_PORT=5434`, `APP_PORT=8091`, or any free ports) — no need to touch `docker-compose.yml`.

**Note:** the AI reviewer's `cli` provider (shells out to the Claude Code CLI) only works when running the app directly on a host that has Claude Code installed and authenticated — not inside the app container. The `api` provider (the default) works either way.

**If you're re-running this after pulling a schema change and already had the stack up before:** reset the Postgres volume once (`docker compose down -v`) before `docker compose up --build` again. `ddl-auto=update` can't safely add a new non-nullable column to a table that already has rows.

For active development without rebuilding the image on every change:

```bash
docker compose up -d spend-guard-db   # just Postgres, on localhost:5433
./gradlew bootRun                     # API on :8080 — any JDK on PATH, Java 21 toolchain auto-downloads if needed
```

CI (`.github/workflows/ci.yml`) compiles and runs the unit test suite on every push/PR.

Try it: `POST /api/admin/seed` to populate synthetic data (2 companies, one of each planted anomaly type), then `GET /api/flags` for the priority queue.

## Development workflow: `coder` / `leader` agents

This project was built with Claude Code using a two-agent review loop, defined in `.claude/agents/`:

- **`coder`** (Sonnet) — implements one task from a precise, written brief. It doesn't expand scope, refactor unrelated code, or invent requirements; it matches the existing code style, writes the tests the brief asks for, and self-reviews its own diff (null/edge cases, stale state, both directions of every config knob) before reporting back.
- **`leader`** (Opus) — reviews the coder's diff with a fresh, adversarial eye *before* anything is accepted: correctness, edge cases, cross-file effects, security, test coverage, scope creep. It never edits code — only reads, runs the project's checks itself, and reports a verdict (`accept` / `accept with nits` / `changes needed`) with concrete findings.

The planning session (this conversation) writes the brief, `coder` implements it, `leader` reviews it, and only a reviewed diff gets accepted. It's the same second-pair-of-eyes discipline you'd want before merging a real PR, applied inside the same coding session.

## What's built

- Real-time hybrid detection: 5 deterministic rule signals (amount z-score, velocity, duplicate, geo anomaly, policy limit) run on every transaction synchronously at ingest; an AI reviewer gives an independent second opinion when the rule score clears a threshold or the transaction involves a merchant the employee has never used before.
- `final_score = max(rule_score, ai_risk_score)`, never a blend — neither signal can cancel the other's alarm.
- Two swappable AI providers (`api` = Anthropic Messages API, `cli` = shells out to Claude Code) via one config line.
- Per-company pricing tier (`STANDARD`/`PREMIUM`) controls both the escalation threshold and which model gets called — real, working "deeper analysis for bigger clients."
- A priority queue (`GET /api/flags`) and an analyst approve/reject workflow (`POST /api/flags/{id}/resolve`).
- A synthetic data generator with one planted anomaly of each type and measurable recall (`caught: true/false` per plant), all routed through the same ingest path real data would use.
- Fully containerized (Postgres + API), Java 21 throughout, zero manual JDK install anywhere.

## What's intentionally left out (and why)

- **Auth, multi-tenant isolation beyond the company FK, pagination.** This is a single-analyst review tool for the exercise, not a multi-customer SaaS product — adding auth wouldn't have demonstrated anything about the detection problem itself.
- **Feedback/retraining loop.** Capturing an analyst's approve/reject decision is already free (it's just a field on the flag). *Using* that history to recalibrate rule weights, or feeding the AI prompt few-shot examples of past verdicts, is a real follow-up project on its own — not built.
- **Full boundary-value test matrix.** The core boundaries (z-score thresholds, the 5-history-item cutoff, identical-timestamp duplicates) are tested; every signal's window boundary (10 min / 30 min / 3 h, both sides) isn't.
- **Input validation richness** (positive-amount check, country code normalization, currency conversion) and **optimistic locking** on resolve — not needed for a single-operator demo with controlled synthetic data.
- **A real card-processor integration.** In production this would be the processor's real-time authorization webhook (how Ramp/Brex/Jeeves itself actually gets transaction data) — never a monthly PDF statement, which is both too retrospective and too unstructured for fraud detection to act on in time.
- **An MCP server** for conversational access to flags from Claude Desktop/Code — a legitimate extension, not core to the detection problem itself.

## Key decisions & trade-offs

- **JPA + `ddl-auto=update`, not jOOQ + Flyway.** jOOQ's type-safe queries and Flyway's controlled schema evolution are the right call for something that will actually run in production and evolve over time; for a single-day prototype, plain JPA removes a whole codegen step without costing correctness here.
- **max(), not a 50/50 blend, for combining rule + AI scores.** Averaging would mean a transaction with a clean rule score needs the AI to say ~99/100 before it ever reaches the review queue — which would silently defeat the entire "AI catches what rules can't" premise this project is about.
- **Novelty (first-time merchant) as a second, independent escalation trigger**, not just a rule-score threshold — without it, a transaction designed to look clean on every numeric signal (the semantic-mismatch case) could never reach the AI at all.
- **Two AI providers instead of one.** The API path is what a real product would ship; the CLI path means anyone evaluating this can see the AI layer actually work without first getting their own Anthropic key.
