package com.spendguard.domain;

/**
 * Simplified stand-in for real MCC (Merchant Category Code) data: a card network assigns one of
 * hundreds of standardized codes at authorization time, not something a company defines itself.
 * {@link #OTHER} is the catch-all for anything that wouldn't map cleanly onto the 6 buckets below
 * in a real integration.
 */
public enum MerchantCategory {
    TRAVEL,
    SOFTWARE,
    MEALS,
    OFFICE,
    TRANSPORT,
    ENTERTAINMENT,
    OTHER
}
