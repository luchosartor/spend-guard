package com.spendguard.ai;

/** Which {@link FraudAiReviewer} a seed run should use - independent of {@code spendguard.ai.provider}. */
public enum AiMode {
    API,
    CLI,
    NONE
}
