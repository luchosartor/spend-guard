package com.spendguard.detection;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.spendguard.domain.GeoAnomalyType;
import org.springframework.stereotype.Component;

/**
 * Deterministic, stateless fraud rule engine. No Spring context is needed to exercise the logic -
 * it's a pure function of {@link RuleEngineInput}, which is why it's unit-tested directly.
 *
 * <p>{@code input.history()} is filtered down to transactions at or before {@code input.occurredAt()}
 * before any signal is computed, so callers don't need to pre-filter and "historical" always means
 * "what had already happened" - never information from the future. The current transaction is
 * expected to have already been excluded from {@code history} by id (not by time), so including
 * same-instant rows here is deliberate: it's what lets the duplicate signal catch a processor retry
 * (the identical payload POSTed twice with the exact same timestamp), which a strict "before" filter
 * would silently miss.
 */
@Component
public class RuleEngine {

    private static final int MIN_HISTORY_FOR_ZSCORE = 5;
    private static final double ZSCORE_LOW = 2.0;
    private static final double ZSCORE_HIGH = 4.0;
    private static final int ZSCORE_MAX_POINTS = 30;

    private static final int VELOCITY_MIN_COUNT = 3;
    private static final Duration VELOCITY_WINDOW = Duration.ofMinutes(10);
    private static final int VELOCITY_POINTS = 20;

    private static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(30);
    private static final int DUPLICATE_POINTS = 25;

    private static final Duration GEO_IMPOSSIBLE_TRAVEL_WINDOW = Duration.ofHours(3);
    private static final int GEO_IMPOSSIBLE_TRAVEL_POINTS = 25;
    private static final int GEO_FIRST_IN_COUNTRY_POINTS = 10;

    private static final int POLICY_POINTS = 20;

    private static final int MAX_SCORE = 100;

    public RuleSignals evaluate(RuleEngineInput input) {
        List<HistoryItem> priorHistory = input.history().stream()
                .filter(h -> !h.occurredAt().isAfter(input.occurredAt()))
                .toList();

        Double amountZScore = computeAmountZScore(input, priorHistory);
        int amountPoints = computeAmountPoints(amountZScore);

        boolean velocityTriggered = computeVelocity(input, priorHistory);
        int velocityPoints = velocityTriggered ? VELOCITY_POINTS : 0;

        boolean duplicateTriggered = computeDuplicate(input, priorHistory);
        int duplicatePoints = duplicateTriggered ? DUPLICATE_POINTS : 0;

        GeoAnomalyType geoAnomalyType = computeGeo(input, priorHistory);
        int geoPoints = switch (geoAnomalyType) {
            case IMPOSSIBLE_TRAVEL -> GEO_IMPOSSIBLE_TRAVEL_POINTS;
            case FIRST_IN_COUNTRY -> GEO_FIRST_IN_COUNTRY_POINTS;
            case NONE -> 0;
        };

        boolean policyViolation = computePolicyViolation(input);
        int policyPoints = policyViolation ? POLICY_POINTS : 0;

        int ruleScore = Math.min(MAX_SCORE, amountPoints + velocityPoints + duplicatePoints + geoPoints + policyPoints);

        return new RuleSignals(amountZScore, velocityTriggered, duplicateTriggered, geoAnomalyType, policyViolation, ruleScore);
    }

    /** Null if fewer than 5 prior same-category transactions exist. */
    private Double computeAmountZScore(RuleEngineInput input, List<HistoryItem> priorHistory) {
        List<BigDecimal> sameCategoryAmounts = priorHistory.stream()
                .filter(h -> h.category() == input.category())
                .map(HistoryItem::amount)
                .toList();
        if (sameCategoryAmounts.size() < MIN_HISTORY_FOR_ZSCORE) {
            return null;
        }

        double mean = sameCategoryAmounts.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double variance = sameCategoryAmounts.stream()
                .mapToDouble(a -> Math.pow(a.doubleValue() - mean, 2))
                .sum() / (sameCategoryAmounts.size() - 1);
        double stdDev = Math.sqrt(variance);
        double amount = input.amount().doubleValue();

        if (stdDev == 0) {
            // A perfectly uniform history gives no variance to divide by; treat any deviation from it
            // as the strongest possible signal rather than silently skipping the check.
            return amount == mean ? 0.0 : 5.0;
        }
        return (amount - mean) / stdDev;
    }

    private int computeAmountPoints(Double zScore) {
        if (zScore == null) {
            return 0;
        }
        double abs = Math.abs(zScore);
        if (abs < ZSCORE_LOW) {
            return 0;
        }
        if (abs >= ZSCORE_HIGH) {
            return ZSCORE_MAX_POINTS;
        }
        double fraction = (abs - ZSCORE_LOW) / (ZSCORE_HIGH - ZSCORE_LOW);
        return (int) Math.round(fraction * ZSCORE_MAX_POINTS);
    }

    /** 3+ transactions (counting the current one) within a 10-minute window ending at this transaction's time. */
    private boolean computeVelocity(RuleEngineInput input, List<HistoryItem> priorHistory) {
        Instant windowStart = input.occurredAt().minus(VELOCITY_WINDOW);
        long countInWindow = priorHistory.stream()
                .filter(h -> !h.occurredAt().isBefore(windowStart))
                .count();
        return countInWindow + 1 >= VELOCITY_MIN_COUNT;
    }

    /** Same merchant, same amount, within 30 minutes of a prior transaction. */
    private boolean computeDuplicate(RuleEngineInput input, List<HistoryItem> priorHistory) {
        return priorHistory.stream().anyMatch(h ->
                Objects.equals(h.merchantId(), input.merchantId())
                        && h.amount().compareTo(input.amount()) == 0
                        && Duration.between(h.occurredAt(), input.occurredAt()).compareTo(DUPLICATE_WINDOW) <= 0);
    }

    private GeoAnomalyType computeGeo(RuleEngineInput input, List<HistoryItem> priorHistory) {
        Instant windowStart = input.occurredAt().minus(GEO_IMPOSSIBLE_TRAVEL_WINDOW);
        boolean impossibleTravel = priorHistory.stream().anyMatch(h ->
                !h.country().equals(input.country()) && !h.occurredAt().isBefore(windowStart));
        if (impossibleTravel) {
            return GeoAnomalyType.IMPOSSIBLE_TRAVEL;
        }

        boolean seenThisCountryBefore = priorHistory.stream().anyMatch(h -> h.country().equals(input.country()));
        if (!seenThisCountryBefore) {
            return GeoAnomalyType.FIRST_IN_COUNTRY;
        }

        return GeoAnomalyType.NONE;
    }

    private boolean computePolicyViolation(RuleEngineInput input) {
        if (input.policyLimit() == null) {
            return false;
        }
        return input.amount().compareTo(input.policyLimit()) > 0;
    }
}
