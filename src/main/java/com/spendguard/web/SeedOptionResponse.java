package com.spendguard.web;

/** One AI mode's status/estimate for the pre-seed screen - part of {@link SeedInfoResponse}. */
public record SeedOptionResponse(boolean available, String unavailableReason, String estimatedCost, String estimatedDuration) {
}
