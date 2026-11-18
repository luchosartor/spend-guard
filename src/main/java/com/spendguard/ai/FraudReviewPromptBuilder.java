package com.spendguard.ai;

import com.spendguard.domain.Employee;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.Transaction;

/**
 * Builds the single prompt sent to Claude by both {@link ApiFraudAiReviewer} and
 * {@link CliFraudAiReviewer} - the two implementations must ask exactly the same question so the
 * only difference between them is transport.
 *
 * <p>Sends the employee's ID, not their name: department/role already carry all the fraud signal
 * a name would add, so sending a real name to a third-party model would be unnecessary PII
 * exposure. The analyst's own UI resolves the ID to a name locally.
 */
public final class FraudReviewPromptBuilder {

    private FraudReviewPromptBuilder() {
    }

    public static String build(ReviewContext context) {
        Transaction transaction = context.transaction();
        Employee employee = context.employee();
        Merchant merchant = context.merchant();
        var signals = context.ruleSignals();

        return """
                You are a fraud risk reviewer for corporate card transactions. A deterministic rule engine has \
                already scored this transaction and escalated it to you for a second opinion. Independently \
                assess the risk that this transaction is fraudulent or a policy violation - do not simply \
                rubber-stamp the rule score. In particular, look for things numeric signals can't see, such as a \
                merchant or spending category that is semantically wrong (for example, a luxury or personal-sounding \
                merchant charged under an "Office Supplies" or "Software" category), even if the amount, velocity, \
                and geography all look completely normal.

                Transaction:
                - Amount: %s %s
                - Occurred at: %s
                - Transaction country: %s
                - Merchant: %s (category: %s, merchant's home country: %s, policy limit: %s)

                Employee:
                - Employee ID: %d
                - Department: %s
                - Role: %s
                - Recent spending pattern: %s

                Rule engine signals already computed:
                - Amount z-score: %s
                - Velocity triggered (3+ transactions within 10 minutes): %s
                - Duplicate charge detected (same merchant/amount within 30 minutes): %s
                - Geo anomaly: %s
                - Policy limit violation: %s
                - Rule score (0-100): %d

                Respond with ONLY a raw JSON object, no prose, no markdown code fences, exactly this shape:
                {"ai_risk_score": <0-100 int>, "semantic_mismatch": <bool>, "explanation": "<2-3 sentences, written for a non-technical analyst>"}
                """.formatted(
                transaction.getAmount(), transaction.getCurrency(),
                transaction.getOccurredAt(),
                transaction.getCountry(),
                merchant.getName(), merchant.getCategory(), merchant.getCountry(),
                merchant.getPolicyLimit() == null ? "none" : merchant.getPolicyLimit(),
                employee.getId(), employee.getDepartment(), employee.getRole(),
                context.spendingSummary(),
                signals.amountZScore() == null ? "n/a (insufficient history)" : "%.2f".formatted(signals.amountZScore()),
                signals.velocityTriggered(),
                signals.duplicateTriggered(),
                signals.geoAnomalyType(),
                signals.policyViolation(),
                signals.ruleScore());
    }
}
