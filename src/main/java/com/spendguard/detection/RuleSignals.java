package com.spendguard.detection;

import com.spendguard.domain.GeoAnomalyType;

/**
 * Raw rule-engine signals plus the combined 0-100 score.
 *
 * @param amountZScore    null if there weren't enough (5+) prior same-category transactions to compute one
 * @param velocityTriggered  3+ transactions within a 10-minute window ending at this transaction
 * @param duplicateTriggered same merchant/amount within 30 minutes
 * @param geoAnomalyType     NONE, IMPOSSIBLE_TRAVEL, or FIRST_IN_COUNTRY (mutually exclusive)
 * @param policyViolation    amount exceeds the merchant's policy limit
 * @param ruleScore          sum of each signal's points, capped at 100
 */
public record RuleSignals(
        Double amountZScore,
        boolean velocityTriggered,
        boolean duplicateTriggered,
        GeoAnomalyType geoAnomalyType,
        boolean policyViolation,
        int ruleScore
) {
}
