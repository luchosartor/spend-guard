package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/transactions} - also what a real card-processor webhook would send. */
public record TransactionRequest(
        @NotNull Long employeeId,
        @NotNull Long merchantId,
        @NotNull BigDecimal amount,
        String currency,
        @NotNull Instant occurredAt,
        @NotNull String country
) {
}
