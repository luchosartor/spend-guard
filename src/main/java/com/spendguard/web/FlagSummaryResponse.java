package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.MerchantCategory;

/**
 * Light-weight row for the priority queue - {@code GET /api/flags}. No AI explanation, no
 * planted-anomaly fields.
 *
 * <p>Includes {@code companyName}: the seed generator reuses the same fixed name/department/role
 * list for each company's employees, so two different people (different companies, different IDs)
 * can share the exact same name - without the company name, they're visually indistinguishable here.
 */
public record FlagSummaryResponse(
        Long id,
        Long transactionId,
        String employeeName,
        String companyName,
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
