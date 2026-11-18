# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it is ingested (simulating a card-processor webhook), combining deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

## Tech Stack

- Java 25, Spring Boot 4.1.1, Gradle 8.14
- PostgreSQL with Spring Data JPA (`ddl-auto=update`, no migration tool — see README trade-offs)
- Lombok
- Local only, no deployment

## Important Rules

- **Keep this file up to date**: when an entity/table or a major module is added, reflect it here.
- Gradle itself needs any JDK on `PATH` to launch (17+); it does not need to be Java 25. The `org.gradle.toolchains.foojay-resolver-convention` plugin (`settings.gradle`) auto-downloads the Java 25 toolchain used to actually compile/run the app on first build — no manual JDK install or hardcoded path needed, on this machine or anyone else's (including CI).
- Detection must stay on the **ingest path**: both the synthetic data generator and any future real feed call the same `TransactionIngestService`. Nothing is pre-labeled before scoring.
- CI (`.github/workflows/ci.yml`) runs compile + unit tests on every push/PR. No DB or live AI key in CI — only tests that don't need either.

## Build & Run

```bash
docker compose up -d          # Postgres on localhost:5433
./gradlew bootRun             # API on :8080
./gradlew test                # unit tests
```

## Project Structure

```
src/main/java/com/spendguard/
  domain/       # JPA entities
  repository/   # Spring Data JPA repositories
  detection/    # rule engine (signals, scoring orchestration)
  ai/           # FraudAiReviewer: Api + Cli implementations
  seed/         # synthetic data generator
  web/          # REST controllers + DTOs + TransactionIngestService
  config/       # AiProperties, AiConfig
  exception/    # NotFoundException, ConflictException (shared across layers)
```

## Database Schema

- `companies` - id, name, plan (`STANDARD` | `PREMIUM`)
- `employees` - id, company_id, full_name, department, role
- `merchants` - id, name, category (`TRAVEL`|`SOFTWARE`|`MEALS`|`OFFICE`|`TRANSPORT`|`ENTERTAINMENT`), country, policy_limit (nullable)
- `cards` - id, employee_id, last4
- `transactions` - id, card_id, merchant_id, amount, currency, occurred_at, country, planted_anomaly, planted_type (internal only, never serialized outside the seed endpoint's own response)
- `transaction_flags` - id, transaction_id (unique), rule_score, ai_risk_score (nullable), semantic_mismatch (nullable), ai_explanation (nullable), final_score, status (`PENDING`|`APPROVED`|`REJECTED`), resolved_at, resolved_note

Rule signal breakdown (amount z-score, velocity/duplicate/geo/policy) is not persisted - `GET /api/flags/{id}` recomputes it on demand via `TransactionScoringService.computeSignals`, since it's a deterministic function of the transaction + its (immutable, backward-looking) history.
