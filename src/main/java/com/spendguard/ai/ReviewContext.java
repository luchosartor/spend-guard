package com.spendguard.ai;

import com.spendguard.detection.RuleSignals;
import com.spendguard.domain.Employee;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.Transaction;

/**
 * Everything a {@link FraudAiReviewer} needs to give an independent second opinion on a transaction.
 *
 * @param spendingSummary short textual summary of the employee's recent spending pattern
 * @param model           the Claude model to use for this review (standard vs. premium, chosen by the caller
 *                         based on the employee's company plan)
 */
public record ReviewContext(
        Transaction transaction,
        Employee employee,
        Merchant merchant,
        RuleSignals ruleSignals,
        String spendingSummary,
        String model
) {
}
