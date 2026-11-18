package com.spendguard.detection;

/** Outcome of scoring one transaction: whether it cleared the review-queue threshold, and the new flag's id. */
public record ScoringResult(boolean flagged, Long flagId) {
}
