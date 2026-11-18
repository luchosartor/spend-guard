package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.MerchantCategory;

/** Light-weight row for the priority queue - {@code GET /api/flags}. No AI explanation, no planted-anomaly fields. */
public record FlagSummaryResponse(
        Long id,
        Long transactionId,
        String employeeName,
        String merchantName,
        MerchantCategory category,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        int ruleScore,
        Integer aiRiskScore,
        Boolean semanticMismatch,
        int finalScore,
        FlagStatus status
) {
}
