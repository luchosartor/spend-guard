package com.spendguard.detection;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.spendguard.ai.AiReviewResult;
import com.spendguard.ai.FraudAiReviewer;
import com.spendguard.ai.ReviewContext;
import com.spendguard.config.AiProperties;
import com.spendguard.domain.Card;
import com.spendguard.domain.Company;
import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.Employee;
import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.Transaction;
import com.spendguard.domain.TransactionFlag;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoring orchestration: runs the rule engine, escalates to the AI reviewer when the employee's
 * company-plan threshold is crossed, and persists a {@link TransactionFlag} when the combined score
 * clears the review-queue bar. Called synchronously, once per transaction, from the ingest path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionScoringService {

    private static final int FLAG_THRESHOLD = 50;

    private final RuleEngine ruleEngine;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final TransactionFlagRepository transactionFlagRepository;
    private final FraudAiReviewer fraudAiReviewer;
    private final AiProperties aiProperties;

    @Transactional
    public ScoringResult score(Transaction transaction) {
        ScoringContext ctx = buildContext(transaction);
        RuleSignals signals = ruleEngine.evaluate(ctx.input());

        boolean premium = ctx.company().getPlan() == CompanyPlan.PREMIUM;
        int threshold = premium ? aiProperties.getPremiumEscalationThreshold() : aiProperties.getStandardEscalationThreshold();
        String model = premium ? aiProperties.getPremiumModel() : aiProperties.getStandardModel();

        Integer aiRiskScore = null;
        Boolean semanticMismatch = null;
        String aiExplanation = null;
        int finalScore = signals.ruleScore();

        // Escalate either because the numeric signals say so, OR because this is the first time
        // this employee has ever transacted with this merchant. The second path exists because a
        // purely semantic mismatch (e.g. a luxury jeweler charged under "Office Supplies") can look
        // completely clean on every numeric signal - ruleScore alone would never cross the
        // threshold, so the rule engine would never hand it to AI, and nothing would ever catch it.
        // Every brand-new merchant relationship gets one mandatory AI look; the AI's semantic read
        // is the only thing that can catch a case like that.
        boolean escalate = signals.ruleScore() >= threshold || ctx.firstTimeWithMerchant();

        if (escalate) {
            String summary = buildSpendingSummary(ctx.priorHistory());
            ReviewContext reviewContext = new ReviewContext(transaction, ctx.employee(), ctx.merchant(), signals, summary, model);
            AiReviewResult aiResult = fraudAiReviewer.review(reviewContext);
            if (aiResult.aiRiskScore() != null) {
                aiRiskScore = aiResult.aiRiskScore();
                semanticMismatch = aiResult.semanticMismatch();
                aiExplanation = aiResult.explanation();
                // The combined score is the STRONGER of the two signals, never an average: we
                // never want one side to be able to cancel out the other's alarm. A confident rule
                // hit must survive even if the AI disagrees (ruleScore=60, AI=5 -> still 60), and a
                // confident AI hit must survive even if rules saw nothing (ruleScore=0, AI=85 ->
                // still 85) - that second case is exactly what the Fix-1 novelty escalation exists
                // for. Both ruleScore and aiRiskScore are still stored on the flag for transparency;
                // only finalScore (the flag-queue ranking/threshold) uses max() instead of a blend.
                finalScore = Math.max(signals.ruleScore(), aiRiskScore);
            } else {
                log.debug("AI review unavailable for transaction {}; falling back to rule-only score {}",
                        transaction.getId(), signals.ruleScore());
            }
        }

        if (finalScore >= FLAG_THRESHOLD) {
            // Persist the signal breakdown alongside the score, rather than recomputing it live on
            // read: nothing orders ingestion by occurredAt, so a late-arriving, earlier-dated
            // transaction scored after this one could make a live recomputation drift from what
            // was actually used to produce ruleScore/aiExplanation. Always show the analyst what
            // this flag was actually scored with.
            TransactionFlag flag = TransactionFlag.builder()
                    .transactionId(transaction.getId())
                    .ruleScore(signals.ruleScore())
                    .amountZScore(signals.amountZScore())
                    .velocityTriggered(signals.velocityTriggered())
                    .duplicateTriggered(signals.duplicateTriggered())
                    .geoAnomalyType(signals.geoAnomalyType())
                    .policyViolation(signals.policyViolation())
                    .aiRiskScore(aiRiskScore)
                    .semanticMismatch(semanticMismatch)
                    .aiExplanation(aiExplanation)
                    .finalScore(finalScore)
                    .status(FlagStatus.PENDING)
                    .build();
            flag = transactionFlagRepository.save(flag);
            return new ScoringResult(true, flag.getId());
        }
        return new ScoringResult(false, null);
    }

    private ScoringContext buildContext(Transaction transaction) {
        Card card = cardRepository.findById(transaction.getCardId())
                .orElseThrow(() -> new NotFoundException("Card not found: " + transaction.getCardId()));
        Employee employee = employeeRepository.findById(card.getEmployeeId())
                .orElseThrow(() -> new NotFoundException("Employee not found: " + card.getEmployeeId()));
        Company company = companyRepository.findById(employee.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + employee.getCompanyId()));
        Merchant merchant = merchantRepository.findById(transaction.getMerchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + transaction.getMerchantId()));

        List<Long> employeeCardIds = cardRepository.findByEmployeeId(employee.getId()).stream()
                .map(Card::getId)
                .toList();
        List<Transaction> otherTransactions = transactionRepository.findByCardIdInAndIdNot(employeeCardIds, transaction.getId());

        Set<Long> merchantIds = new HashSet<>();
        otherTransactions.forEach(t -> merchantIds.add(t.getMerchantId()));
        merchantIds.add(merchant.getId());
        Map<Long, Merchant> merchantsById = merchantRepository.findAllById(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));

        List<HistoryItem> history = otherTransactions.stream()
                .map(t -> new HistoryItem(
                        t.getAmount(),
                        merchantsById.get(t.getMerchantId()).getCategory(),
                        t.getCountry(),
                        t.getOccurredAt(),
                        t.getMerchantId()))
                .toList();

        // Same "has this already happened" semantics as RuleEngine's own filter (!isAfter, not
        // strict isBefore) - this list only feeds the AI's spending-summary text, but there's no
        // reason for the two "what counts as history" definitions in this codebase to disagree.
        List<HistoryItem> priorHistory = history.stream()
                .filter(h -> !h.occurredAt().isAfter(transaction.getOccurredAt()))
                .toList();

        boolean firstTimeWithMerchant = !transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(
                employeeCardIds, merchant.getId(), transaction.getOccurredAt());

        RuleEngineInput input = new RuleEngineInput(
                transaction.getAmount(),
                merchant.getCategory(),
                transaction.getCountry(),
                transaction.getOccurredAt(),
                merchant.getId(),
                merchant.getPolicyLimit(),
                history);

        return new ScoringContext(employee, merchant, company, input, priorHistory, firstTimeWithMerchant);
    }

    private String buildSpendingSummary(List<HistoryItem> priorHistory) {
        if (priorHistory.isEmpty()) {
            return "No prior transaction history for this employee.";
        }
        Map<MerchantCategory, List<HistoryItem>> byCategory = priorHistory.stream()
                .collect(Collectors.groupingBy(HistoryItem::category));
        StringBuilder summary = new StringBuilder("Recent spending pattern (" + priorHistory.size() + " prior transactions): ");
        byCategory.forEach((category, items) -> {
            double avg = items.stream().mapToDouble(h -> h.amount().doubleValue()).average().orElse(0);
            summary.append(category).append(": ").append(items.size()).append(" txns, avg $")
                    .append("%.2f".formatted(avg)).append("; ");
        });
        return summary.toString();
    }

    private record ScoringContext(
            Employee employee, Merchant merchant, Company company, RuleEngineInput input,
            List<HistoryItem> priorHistory, boolean firstTimeWithMerchant) {
    }
}
