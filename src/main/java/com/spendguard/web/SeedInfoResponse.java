package com.spendguard.web;

/** Read-only status for the pre-seed screen: what running the seed will cost and roughly how long it'll take. */
public record SeedInfoResponse(
        String provider,
        boolean willSpendCredits,
        String estimatedCost,
        String estimatedDuration,
        String note
) {
}
