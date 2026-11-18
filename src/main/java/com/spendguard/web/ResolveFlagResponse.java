package com.spendguard.web;

import java.time.Instant;

import com.spendguard.domain.FlagStatus;

public record ResolveFlagResponse(Long id, FlagStatus status, Instant resolvedAt, String resolvedNote) {
}
