# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it is ingested (simulating a card-processor webhook), combining deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

## Tech Stack

- Java 21, Spring Boot 4.1.1, Gradle 8.14
- PostgreSQL with Spring Data JPA (`ddl-auto=update`, no migration tool — see README trade-offs)
- Lombok
- Local only, no deployment

## Important Rules

- **Keep this file up to date**: when an entity/table or a major module is added, reflect it here.
- Everything targets Java 21 — one JDK everywhere, no toolchain split. The `org.gradle.toolchains.foojay-resolver-convention` plugin (`settings.gradle`) auto-downloads it on first build if the host's default JDK isn't 21 — no manual install or hardcoded path needed, on this machine or anyone else's (including CI).
- Detection must stay on the **ingest path**: both the synthetic data generator and any future real feed call the same `TransactionIngestService`. Nothing is pre-labeled before scoring.
- CI (`.github/workflows/ci.yml`) runs compile + unit tests on every push/PR. No DB or live AI key in CI — only tests that don't need either.

## Build & Run

```bash
docker compose up --build         # everything: Postgres on :5433, API on :8080 (override via DB_PORT/APP_PORT in .env)
# or, for active development:
docker compose up -d spend-guard-db   # just Postgres on :5433
./gradlew bootRun                     # API on :8080
./gradlew test                        # unit tests
```

The `spendguard.ai.provider: cli` mode (shells out to the Claude Code CLI) only works on the `bootRun` path, not inside the app container — the container has no Claude Code install/auth.

## Project Structure

```
src/main/java/com/spendguard/
  domain/       # JPA entities + enums (CompanyPlan, MerchantCategory, PlantedType, FlagStatus, GeoAnomalyType)
  repository/   # Spring Data JPA repositories
  detection/    # rule engine (signals, scoring orchestration)
  ai/           # FraudAiReviewer: Api + Cli implementations
  seed/         # synthetic data generator
  web/          # REST controllers + DTOs + TransactionIngestService
  config/       # AiProperties, AiConfig
  exception/    # NotFoundException, ConflictException (shared across layers)
```

## Database Schema

- `companies` - id, name, plan (`STANDARD` | `PREMIUM` — PREMIUM gets a lower AI-escalation threshold and a stronger model)
- `employees` - id, company_id (FK → companies), full_name, department, role
- `merchants` - id, name, category (`TRAVEL`|`SOFTWARE`|`MEALS`|`OFFICE`|`TRANSPORT`|`ENTERTAINMENT`), country, policy_limit (nullable = no limit)
- `cards` - id, employee_id (FK → employees), last4
- `transactions` - id, card_id (FK → cards), merchant_id (FK → merchants), amount, currency, occurred_at, country, planted_anomaly, planted_type (`AMOUNT_OUTLIER`|`VELOCITY`|`DUPLICATE`|`GEO`|`POLICY`|`SEMANTIC_MISMATCH` — both internal only, never serialized outside the seed endpoint's own response)
- `transaction_flags` - id, transaction_id (FK → transactions, unique — one flag per transaction, only created once `final_score >= 50`), rule_score, amount_z_score (nullable), velocity_triggered, duplicate_triggered, geo_anomaly_type (`NONE`|`IMPOSSIBLE_TRAVEL`|`FIRST_IN_COUNTRY`), policy_violation, ai_risk_score (nullable — null if never escalated to AI), semantic_mismatch (nullable), ai_explanation (nullable), final_score (`max(rule_score, ai_risk_score)` if AI ran, else `rule_score` — never a blend, so neither signal can cancel the other's alarm), status (`PENDING`|`APPROVED`|`REJECTED`), resolved_at, resolved_note

Every FK `Long` column has an explicit `@Column(name=...)` matching its read-only `@ManyToOne`/`@OneToOne`'s `@JoinColumn(name=...)` — without that, Hibernate sees two different logical names for one physical column and throws `DuplicateMappingException` at startup. The relations exist only so `ddl-auto=update` generates real FK constraints; the rest of the codebase reads/writes the plain `Long` fields, never the relations. `JpaMappingSmokeTest` boots a real (H2) persistence context specifically to catch this class of bug in `./gradlew test`/CI, since nothing else exercises Hibernate metadata.

The rule signal breakdown on `transaction_flags` is persisted at scoring time, not recomputed live on read — `GET /api/flags/{id}` returns the stored columns, since nothing orders ingestion by `occurred_at` and a live recompute could drift from the score it's displayed next to.
