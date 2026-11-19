package com.spendguard.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpFraudAiReviewerTest {

    @Test
    void alwaysReportsUnavailable() {
        AiReviewResult result = new NoOpFraudAiReviewer().review(null);

        assertThat(result.aiRiskScore()).isNull();
        assertThat(result.semanticMismatch()).isFalse();
        assertThat(result.explanation()).contains("mode=none");
    }
}
