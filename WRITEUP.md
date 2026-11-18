# Spend Guard — 1-page write-up

## Problem

Corporate card platforms (Ramp, Brex) ship real-time AI-based anomaly detection on top of static spend rules; based on available product documentation, Jeeves does not yet have this layer. Spend Guard is a working prototype of that gap: a fraud/anomaly detection pipeline that scores every corporate card transaction the instant it's ingested — simulating a card processor's real-time authorization webhook — and surfaces anything suspicious in a priority queue for a human analyst to approve or reject.

## Approach

Each transaction goes through a **hybrid pipeline**, not rules-then-AI-as-afterthought:

1. **Rule engine** (deterministic, synchronous, 5 signals) — amount z-score vs. that employee's own category history, velocity, duplicate charge, geo anomaly (impossible travel / first-ever transaction in a country), and merchant policy-limit violation. Produces a 0–100 `rule_score`.
2. **AI reviewer** (Claude) — gives an *independent* risk verdict (0–100 + explanation), not just prose commentary on the rule output. It only runs when escalation is warranted, to control latency/cost: `rule_score` clears a per-company threshold, **or** it's the employee's first-ever transaction with that merchant. The second trigger exists specifically so a transaction that's clean on every numeric signal but semantically wrong (e.g. a travel employee's category history looks normal, but the merchant itself doesn't fit their role) can still reach the AI.
3. **Combine:** `final_score = max(rule_score, ai_risk_score)` — never a weighted average. A blend would mean the AI alone could almost never trigger a flag (it would need to score ~99 when rules say 0), which defeats the point of having it as an independent opinion.

A transaction is flagged for review when `final_score >= 50`.

**Tiered depth by plan:** `PREMIUM` companies get a lower escalation threshold and a stronger model than `STANDARD` ones — a real, working version of "more scrutiny for bigger clients," not just a pricing tier on paper.

Two swappable AI providers, picked by one config line: the Anthropic Messages API (what a real product would ship) or the Claude Code CLI (so this can be evaluated end-to-end without first provisioning an Anthropic key).

## What's built vs. left out

Built: the full ingest → score → flag → resolve loop, both AI providers, per-plan tiering, a synthetic data generator with one planted anomaly per signal type (with measurable recall), fully containerized (Docker), Java 21, CI running the test suite on every push.

Deliberately left out (documented with reasoning in the README, not just dropped silently): auth/multi-tenant isolation, the feedback/retraining loop (capturing an analyst's verdict is free; using it to recalibrate rule weights or prompt few-shot examples is a real follow-on project), a real card-processor integration (this would be a live authorization webhook in production, never a monthly PDF statement — too retrospective for fraud detection), and an MCP server for conversational access to flags. None of these would have demonstrated anything new about the core detection problem; all were cut to keep the one-day scope honest.

## Key trade-offs

- **Plain JPA (`ddl-auto=update`), not jOOQ/Flyway** — the right call for production, unnecessary ceremony for a single-day prototype.
- **`max()`, not a blend**, for combining rule and AI scores — see above; this was the single most important correctness decision in the whole design.
- **Novelty as a second escalation trigger**, independent of the rule score — without it, a rule-clean-but-semantically-wrong transaction could never reach the AI at all.
- **Simplified `MerchantCategory` enum instead of real MCC codes** — real MCC data is hundreds of card-network-assigned codes, not something a company defines; a fixed enum keeps the demo tractable while the README is explicit about what a real integration would need to do instead.

## Try it

```bash
docker compose up --build
curl -X POST localhost:8080/api/admin/seed
curl localhost:8080/api/flags
```
