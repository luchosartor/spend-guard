package com.spendguard.web;

import java.math.BigDecimal;

import com.spendguard.domain.MerchantCategory;

public record MerchantInfoResponse(Long id, String name, MerchantCategory category, String country, BigDecimal policyLimit) {
}
