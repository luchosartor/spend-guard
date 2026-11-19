package com.spendguard.web;

/** Read-only status for the pre-seed screen: cost/time estimate and availability for each AI mode. */
public record SeedInfoResponse(SeedOptionResponse api, SeedOptionResponse cli, SeedOptionResponse none) {
}
