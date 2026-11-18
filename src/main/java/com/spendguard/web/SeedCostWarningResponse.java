package com.spendguard.web;

/** Returned instead of running the seed when it would spend real Anthropic API credits and the caller hasn't confirmed. */
public record SeedCostWarningResponse(String warning, String howToProceed) {
}
