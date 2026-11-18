package com.spendguard.domain;

/**
 * Which (if any) of the two mutually-exclusive geo signals fired for a transaction. Lives in
 * {@code domain} (not {@code detection}, where the rule engine that computes it lives) because
 * it's persisted on {@link TransactionFlag} - domain entities shouldn't need to reach into the
 * detection package for a type they store.
 */
public enum GeoAnomalyType {
    NONE,
    /** A prior transaction in a different country within the last 3 hours (impossible travel). */
    IMPOSSIBLE_TRAVEL,
    /** The employee's first-ever transaction in this country. */
    FIRST_IN_COUNTRY
}
