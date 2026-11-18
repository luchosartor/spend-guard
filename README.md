# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it's ingested — simulating a card-processor webhook — by a hybrid pipeline that combines deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

## Running locally

Everything runs in Docker — the only requirement is Docker itself.

```bash
docker compose up --build   # Postgres on :5433, API on :8080
```

If you have an Anthropic API key and want the AI reviewer to actually call Claude, put it in a `.env` file next to `docker-compose.yml` (`ANTHROPIC_API_KEY=...`, gitignored, never committed) before running the command above — `docker compose` picks it up automatically. Without a key, detection still runs end-to-end on rules alone (the AI reviewer reports "unavailable" and the service falls back to rule-only scoring, by design) — and if `spendguard.ai.provider=api` with no key set, this is logged as an explicit warning at startup, not just discovered silently on the first escalated transaction.

If `5433` or `8080` are already taken on your machine, override them in the same `.env` file (`DB_PORT=5434`, `APP_PORT=8091`, or any free ports) — no need to touch `docker-compose.yml`.

**Note:** the AI reviewer's `cli` provider (shells out to the Claude Code CLI) only works when running the app directly on a host that has Claude Code installed and authenticated — not inside the app container. It exists strictly so this project can be evaluated end-to-end without first provisioning an Anthropic API key; it is **not a production-viable option** (shelling out per transaction doesn't scale, and it ties the service to one authenticated host). The `api` provider (the default) is what a real deployment would use, and works either way. If you already have a Claude subscription: a $100/month Claude Max plan also includes $100/month in API credits, so evaluating with the `api` provider may not cost anything beyond that subscription.

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

## Detection rules

Rule engine, 0-100 per transaction (capped), each signal computed against that employee's own backward-looking history (never future-dated, regardless of DB insertion order):

| Signal | Condition | Points |
|---|---|---|
| Amount z-score | vs. that employee+category's historical mean/stddev (needs 5+ prior same-category transactions, else skipped) | 0 if \|z\|<2, linear 0→30 for \|z\| in [2,4], 30 if \|z\|≥4 |
| Velocity | 3+ transactions by the employee within a 10-min window | flat 20 |
| Duplicate | same employee+merchant+amount within 30 min (including an identical-timestamp processor retry) | flat 25 |
| Geo — impossible travel | a prior transaction in a different country within the last 3 hours | flat 25 |
| Geo — first in country | employee's first-ever transaction in this country (not combined with the above) | flat 10 |
| Policy | amount > merchant's policy limit (skipped if no limit) | flat 20 |

**Escalation to AI** happens when either `rule_score >= threshold` (`20` for `STANDARD` companies, `10` for `PREMIUM`) **or** it's the employee's first-ever transaction with this merchant (novelty) — the novelty path is what lets a transaction that's clean on every signal above still reach the AI (see the semantic-mismatch plant in the seed data).

**Combining the two:** `final_score = max(rule_score, ai_risk_score)` if the AI ran, else `final_score = rule_score`. A flag is created only if `final_score >= 50`.

**AI cost per escalated transaction** (Anthropic list pricing; `MAX_TOKENS=300` caps the output, actual output is usually smaller since the response is a short JSON object):

| Plan | Model | Input $/1M tok | Output $/1M tok | Approx. cost per call |
|---|---|---|---|---|
| `STANDARD` | `claude-haiku-5-5` | $0.10 | $0.50 | ~$0.0002 |
| `PREMIUM` | `claude-sonnet-5-5` | $2.00 | $10.00 | ~$0.004 (~20x `STANDARD`) |

The lower escalation threshold for `PREMIUM` plus the stronger (and ~20x pricier) model is a real cost/depth trade-off per plan, not just a label.

## What's intentionally left out (and why)

- **Auth, multi-tenant isolation beyond the company FK, pagination.** This is a single-analyst review tool for the exercise, not a multi-customer SaaS product — adding auth wouldn't have demonstrated anything about the detection problem itself.
- **Feedback/retraining loop.** Capturing an analyst's approve/reject decision is already free (it's just a field on the flag). *Using* that history to recalibrate rule weights, or feeding the AI prompt few-shot examples of past verdicts, is a real follow-up project on its own — not built.
- **Full boundary-value test matrix.** The core boundaries (z-score thresholds, the 5-history-item cutoff, identical-timestamp duplicates) are tested; every signal's window boundary (10 min / 30 min / 3 h, both sides) isn't.
- **Input validation richness** (positive-amount check, country code normalization, currency conversion) and **optimistic locking** on resolve — not needed for a single-operator demo with controlled synthetic data.
- **A real card-processor integration.** In production this would be the processor's real-time authorization webhook (how Ramp/Brex/Jeeves itself actually gets transaction data) — never a monthly PDF statement, which is both too retrospective and too unstructured for fraud detection to act on in time.
- **An MCP server** for conversational access to flags from Claude Desktop/Code — a legitimate extension, not core to the detection problem itself.
- **`MerchantCategory` as a fixed 6-value enum, not real MCC codes.** In production, merchant category comes from the card network's MCC (Merchant Category Code) at authorization time — hundreds of standardized codes, not something a company defines itself. A fixed enum is a deliberate simplification to keep the demo data tractable; a real integration would map MCCs to broader buckets (or work with the raw code directly), and would include an `OTHER` catch-all for anything unmapped.

## Key decisions & trade-offs

- **JPA + `ddl-auto=update`, not jOOQ + Flyway.** jOOQ's type-safe queries and Flyway's controlled schema evolution are the right call for something that will actually run in production and evolve over time; for a single-day prototype, plain JPA removes a whole codegen step without costing correctness here.
- **max(), not a 50/50 blend, for combining rule + AI scores.** Averaging would mean a transaction with a clean rule score needs the AI to say ~99/100 before it ever reaches the review queue — which would silently defeat the entire "AI catches what rules can't" premise this project is about.
- **Novelty (first-time merchant) as a second, independent escalation trigger**, not just a rule-score threshold — without it, a transaction designed to look clean on every numeric signal (the semantic-mismatch case) could never reach the AI at all.
- **Two AI providers instead of one.** The API path is what a real product would ship; the CLI path means anyone evaluating this can see the AI layer actually work without first getting their own Anthropic key.
- **Raw HTTP (`java.net.http.HttpClient`), not the Anthropic Java SDK, for the `api` provider.** The whole call is one `POST` with two headers and a JSON body; pulling in a full SDK dependency for that is more than the problem needs. A production build would move to the SDK for its retry/streaming/error-typing — not needed for a single synchronous call per escalated transaction here.
- **Employee ID, not name, in the AI prompt.** `department`/`role` already carry all the fraud signal a name would add (e.g. an Engineering employee buying something luxury-looking under "Software"); a name adds no detection value, only unnecessary PII sent to a third-party model. The analyst's own UI resolves the ID to a name locally.
- **No prompt caching.** Each AI review is one independent call per transaction, not a multi-turn conversation — there's no accumulating history to reuse, and a transaction's context shouldn't bleed into another's judgment anyway. The one part of the prompt that *is* shared across calls (the fixed reviewer instructions) is only ~150-200 tokens — below the 512-token minimum cacheable prefix on Haiku 5.5/Sonnet 5.5, so it would silently never cache even with a `cache_control` marker. Padding it just to clear that threshold isn't worth it at a cost this low (see the per-call pricing above).
