package com.spendguard.domain;

/**
 * Tags the kind of anomaly the synthetic data generator planted on a {@link Transaction}.
 * Internal only: never serialized in analyst-facing DTOs, only in the seed endpoint's own response.
 */
public enum PlantedType {
    AMOUNT_OUTLIER,
    VELOCITY,
    DUPLICATE,
    GEO,
    POLICY,
    SEMANTIC_MISMATCH
}
