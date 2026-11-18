package com.spendguard.ai;

/**
 * Result of an AI second opinion.
 *
 * @param aiRiskScore 0-100, or null if the AI reviewer was unavailable or its response couldn't be
 *                     parsed - callers must fall back to rule-only scoring in that case.
 */
public record AiReviewResult(Integer aiRiskScore, boolean semanticMismatch, String explanation) {

    public static AiReviewResult unavailable(String reason) {
        return new AiReviewResult(null, false, reason);
    }
}
