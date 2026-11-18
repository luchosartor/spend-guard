package com.spendguard.ai;

/** Second opinion on a transaction's fraud risk, given after the rule engine escalates it. */
public interface FraudAiReviewer {

    /**
     * Never throws: any failure (missing key, network error, timeout, malformed response) is
     * reported as an {@link AiReviewResult} with a null {@code aiRiskScore}, so the caller can
     * fall back to rule-only scoring.
     */
    AiReviewResult review(ReviewContext context);
}
