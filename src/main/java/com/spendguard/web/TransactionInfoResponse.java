package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionInfoResponse(Long id, BigDecimal amount, String currency, Instant occurredAt, String country) {
}
