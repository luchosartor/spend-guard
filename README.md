# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it's ingested — simulating a card-processor webhook — by a hybrid pipeline that combines deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

_(Problem statement, approach and demo instructions go here — filled in once the backend lands.)_

## Running locally

Requirements: Docker, and any JDK on `PATH` to launch Gradle — the Java 21 toolchain used to actually compile/run the app downloads automatically on first build if it's not already present (no manual JDK install needed).

```bash
docker compose up -d   # Postgres on localhost:5433
./gradlew bootRun      # API on :8080
```

CI (`.github/workflows/ci.yml`) compiles and runs the unit test suite on every push/PR.

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
