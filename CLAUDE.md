# Spend Guard

Real-time fraud/anomaly detection for corporate card spend. Every transaction is scored the moment it is ingested (simulating a card-processor webhook), combining deterministic rules with an AI second opinion, and surfaced in a priority queue for a human analyst to approve or reject.

## Tech Stack

- Java 25, Spring Boot 4.1.1, Gradle 8.14
- PostgreSQL with Spring Data JPA (`ddl-auto=update`, no migration tool — see README trade-offs)
- Lombok
- Local only, no deployment

## Important Rules

- **Keep this file up to date**: when an entity/table or a major module is added, reflect it here.
- Gradle itself must run on an installed JDK (21, Microsoft build, already on PATH). Only the **toolchain** used to compile/run the app is Java 25 (`gradle.properties` registers `/opt/homebrew/opt/openjdk@25` as an additional installation). Do not set `org.gradle.java.home` to the JDK 25 path — Gradle 8.14 cannot run its own daemon on Java 25 yet.
- Detection must stay on the **ingest path**: both the synthetic data generator and any future real feed call the same `TransactionIngestService`. Nothing is pre-labeled before scoring.

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
  detection/    # rule engine (signals, scoring)
  ai/           # FraudAiReviewer: Api + Cli implementations
  seed/         # synthetic data generator
  web/          # REST controllers + DTOs
  config/       # AiProperties, etc.
```

## Database Schema

_(filled in as entities land)_
