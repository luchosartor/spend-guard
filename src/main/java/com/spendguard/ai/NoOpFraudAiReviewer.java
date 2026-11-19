package com.spendguard.ai;

/** Always reports unavailable, with no network call or subprocess - lets a seed run skip AI entirely. */
public class NoOpFraudAiReviewer implements FraudAiReviewer {

    @Override
    public AiReviewResult review(ReviewContext context) {
        return AiReviewResult.unavailable("AI unavailable: mode=none (rule-only run)");
    }
}
