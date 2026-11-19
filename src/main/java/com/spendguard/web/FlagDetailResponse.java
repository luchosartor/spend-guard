package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.MerchantCategory;

/** Full detail for one flag - {@code GET /api/flags/{id}}. */
public record FlagDetailResponse(
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
        String aiExplanation,
        int finalScore,
        FlagStatus status,
        SignalBreakdownResponse signals,
        EmployeeInfoResponse employee,
        MerchantInfoResponse merchant,
        TransactionInfoResponse transaction
) {
}
