package com.spendguard.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.spendguard.domain.MerchantCategory;

/**
 * Everything {@link RuleEngine} needs to score one transaction: the transaction's own fields,
 * the merchant's policy limit, and the employee's other transactions (in any order - the engine
 * filters {@code history} down to items strictly before {@code occurredAt} itself, so "historical"
 * signals never leak information from transactions that haven't happened yet).
 */
public record RuleEngineInput(
        BigDecimal amount,
        MerchantCategory category,
        String country,
        Instant occurredAt,
        Long merchantId,
        BigDecimal policyLimit,
        List<HistoryItem> history
) {
}
