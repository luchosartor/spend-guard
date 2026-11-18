package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.domain.MerchantCategory;

public record EmployeeTransactionResponse(
        Long id,
        Long merchantId,
        String merchantName,
        MerchantCategory category,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        String country
) {
}
