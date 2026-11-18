# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it's ingested — simulating a card-processor webhook — by a hybrid pipeline that combines deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

_(Problem statement, approach and demo instructions go here — filled in once the backend lands.)_

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

## Development workflow: `coder` / `leader` agents

This project was built with Claude Code using a two-agent review loop, defined in `.claude/agents/`:

- **`coder`** (Sonnet) — implements one task from a precise, written brief. It doesn't expand scope, refactor unrelated code, or invent requirements; it matches the existing code style, writes the tests the brief asks for, and self-reviews its own diff (null/edge cases, stale state, both directions of every config knob) before reporting back.
- **`leader`** (Opus) — reviews the coder's diff with a fresh, adversarial eye *before* anything is accepted: correctness, edge cases, cross-file effects, security, test coverage, scope creep. It never edits code — only reads, runs the project's checks itself, and reports a verdict (`accept` / `accept with nits` / `changes needed`) with concrete findings.

The planning session (this conversation) writes the brief, `coder` implements it, `leader` reviews it, and only a reviewed diff gets accepted. It's the same second-pair-of-eyes discipline you'd want before merging a real PR, applied inside the same coding session.

## What's built

_(filled in after implementation)_

## What's intentionally left out (and why)

_(filled in after implementation — includes the feedback/retraining loop and the MCP extension idea)_

## Key decisions & trade-offs

_(filled in after implementation)_

## Next steps

- Feedback loop: use analyst approve/reject decisions to recalibrate rule weights or feed the AI prompt few-shot examples of past verdicts. Capturing the decision is trivial (already done); using it to retrain is the part left out.
- MCP server exposing read-only fraud tools (`get_flag`, `list_pending_flags`) so an analyst could query alerts conversationally from Claude Desktop/Code.
