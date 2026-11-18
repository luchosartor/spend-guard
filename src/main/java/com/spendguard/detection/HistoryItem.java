package com.spendguard.detection;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.domain.MerchantCategory;

/**
 * One other transaction by the same employee, flattened with the fields the rule engine needs
 * (merchant category/country resolved, rather than just ids).
 */
public record HistoryItem(
        BigDecimal amount,
        MerchantCategory category,
        String country,
        Instant occurredAt,
        Long merchantId
) {
}
