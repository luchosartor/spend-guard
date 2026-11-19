package com.spendguard.detection;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.spendguard.ai.AiReviewResult;
import com.spendguard.ai.FraudAiReviewer;
import com.spendguard.config.AiProperties;
import com.spendguard.domain.Card;
import com.spendguard.domain.Company;
import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.Employee;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.Transaction;
import com.spendguard.domain.TransactionFlag;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Fix-1 "first time with this merchant" escalation path (AI must be called even when
 * ruleScore is 0, but only for a genuinely new merchant relationship) and the Blocker-2 max()
 * combination (neither signal can cancel the other out).
 */
@ExtendWith(MockitoExtension.class)
class TransactionScoringServiceTest {

    @Mock
    private CardRepository cardRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private TransactionFlagRepository transactionFlagRepository;
    @Mock
    private FraudAiReviewer fraudAiReviewer;
    @Mock
    private FraudAiReviewer overrideReviewer;

    private TransactionScoringService service;

    private final Instant now = Instant.parse("2026-11-18T12:00:00Z");
    private final Card card = Card.builder().id(1L).employeeId(10L).last4("1234").build();
    private final Employee employee = Employee.builder()
            .id(10L).companyId(100L).fullName("Test Employee").department("Engineering").role("SWE").build();
    private final Company company = Company.builder().id(100L).name("Acme").plan(CompanyPlan.STANDARD).build();
    private final Merchant merchant = Merchant.builder()
            .id(500L).name("New Shop").category(MerchantCategory.OFFICE).country("US")
            .policyLimit(new BigDecimal("1000.00")).build();
    private final Merchant otherMerchant = Merchant.builder()
            .id(999L).name("Familiar Shop").category(MerchantCategory.MEALS).country("US").build();

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.setStandardEscalationThreshold(20);
        aiProperties.setPremiumEscalationThreshold(10);
        aiProperties.setStandardModel("standard-model");
        aiProperties.setPremiumModel("premium-model");

        service = new TransactionScoringService(new RuleEngine(), cardRepository, transactionRepository,
                merchantRepository, employeeRepository, companyRepository, transactionFlagRepository,
                fraudAiReviewer, aiProperties);
    }

    @Test
    void firstTimeWithMerchant_escalatesToAiEvenWithZeroRuleScore() {
        Transaction transaction = transactionAgainst(merchant);
        // A prior transaction with a DIFFERENT merchant (so this one is still the employee's first
        // with `merchant`) but the SAME country, so the geo signal stays at 0 and ruleScore is
        // genuinely 0 - isolating the novelty path from any rule-score contribution.
        Transaction priorWithOtherMerchant = Transaction.builder().id(2L).cardId(1L).merchantId(999L)
                .amount(new BigDecimal("40.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofDays(10))).country("US").build();

        stubCommonLookups(List.of(priorWithOtherMerchant));
        when(transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(List.of(1L), 500L, now))
                .thenReturn(false); // never transacted with this merchant before
        when(fraudAiReviewer.review(any())).thenReturn(AiReviewResult.unavailable("test"));

        service.score(transaction);

        verify(fraudAiReviewer).review(any());
    }

    @Test
    void reviewerOverride_isUsedInsteadOfTheDefaultReviewer() {
        Transaction transaction = transactionAgainst(merchant);
        Transaction priorWithOtherMerchant = Transaction.builder().id(2L).cardId(1L).merchantId(999L)
                .amount(new BigDecimal("40.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofDays(10))).country("US").build();

        stubCommonLookups(List.of(priorWithOtherMerchant));
        when(transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(List.of(1L), 500L, now))
                .thenReturn(false); // novel merchant -> escalates via Fix-1 regardless of ruleScore
        when(overrideReviewer.review(any())).thenReturn(AiReviewResult.unavailable("test"));

        service.score(transaction, overrideReviewer);

        verify(overrideReviewer).review(any());
        verify(fraudAiReviewer, never()).review(any());
    }

    @Test
    void familiarMerchant_doesNotEscalateWhenRuleScoreIsZero() {
        Transaction transaction = transactionAgainst(merchant);
        Transaction priorWithSameMerchant = Transaction.builder().id(2L).cardId(1L).merchantId(500L)
                .amount(new BigDecimal("48.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofDays(10))).country("US").build();

        stubCommonLookups(List.of(priorWithSameMerchant));
        when(transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(List.of(1L), 500L, now))
                .thenReturn(true); // already transacted with this merchant before

        service.score(transaction);

        verify(fraudAiReviewer, never()).review(any());
    }

    @Test
    void aiHighRiskAloneFlagsDespiteZeroRuleScore() {
        Transaction transaction = transactionAgainst(merchant);
        Transaction priorWithOtherMerchant = Transaction.builder().id(2L).cardId(1L).merchantId(999L)
                .amount(new BigDecimal("40.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofDays(10))).country("US").build();

        stubCommonLookups(List.of(priorWithOtherMerchant));
        when(transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(List.of(1L), 500L, now))
                .thenReturn(false); // novel merchant -> escalates via Fix-1 regardless of ruleScore
        when(fraudAiReviewer.review(any()))
                .thenReturn(new AiReviewResult(85, true, "Looks like a semantic mismatch."));
        when(transactionFlagRepository.save(any())).thenAnswer(invocation -> {
            TransactionFlag flag = invocation.getArgument(0);
            flag.setId(1L);
            return flag;
        });

        ScoringResult result = service.score(transaction);

        // ruleScore is 0 (clean merchant, clean history) - under the old 50/50 blend this would
        // have needed an AI score of 99+ to ever cross the flag line. max(0, 85) = 85 flags it.
        assertThat(result.flagged()).isTrue();
        ArgumentCaptor<TransactionFlag> captor = ArgumentCaptor.forClass(TransactionFlag.class);
        verify(transactionFlagRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleScore()).isZero();
        assertThat(captor.getValue().getAiRiskScore()).isEqualTo(85);
        assertThat(captor.getValue().getSemanticMismatch()).isTrue();
        assertThat(captor.getValue().getFinalScore()).isEqualTo(85);
    }

    @Test
    void ruleScoreAloneStillFlagsWhenAiDisagrees() {
        Merchant officeMerchant = Merchant.builder().id(500L).name("Office Shop").category(MerchantCategory.OFFICE)
                .country("US").policyLimit(new BigDecimal("100.00")).build();
        Merchant mealsMerchant = Merchant.builder().id(777L).name("Cafe").category(MerchantCategory.MEALS)
                .country("US").build();

        Transaction transaction = Transaction.builder().id(1L).cardId(1L).merchantId(500L)
                .amount(new BigDecimal("200.00")).currency("USD").occurredAt(now).country("US").build();
        // Same merchant, same amount, 10 minutes earlier -> duplicate (25 pts).
        Transaction duplicateSetup = Transaction.builder().id(2L).cardId(1L).merchantId(500L)
                .amount(new BigDecimal("200.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofMinutes(10))).country("US").build();
        // A different merchant/category 5 minutes earlier -> makes 3 transactions within the
        // 10-minute velocity window (20 pts) without touching the OFFICE z-score pool or the
        // duplicate check (different merchant and amount).
        Transaction velocitySetup = Transaction.builder().id(3L).cardId(1L).merchantId(777L)
                .amount(new BigDecimal("15.00")).currency("USD")
                .occurredAt(now.minus(Duration.ofMinutes(5))).country("US").build();

        when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(companyRepository.findById(100L)).thenReturn(Optional.of(company));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(officeMerchant));
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(transactionRepository.findByCardIdInAndIdNot(List.of(1L), 1L))
                .thenReturn(List.of(duplicateSetup, velocitySetup));
        when(merchantRepository.findAllById(any())).thenReturn(List.of(officeMerchant, mealsMerchant));
        when(transactionRepository.existsByCardIdInAndMerchantIdAndOccurredAtBefore(List.of(1L), 500L, now))
                .thenReturn(true); // familiar merchant - escalates via the rule-score threshold, not novelty
        when(fraudAiReviewer.review(any())).thenReturn(new AiReviewResult(5, false, "Looks fine to me."));
        when(transactionFlagRepository.save(any())).thenAnswer(invocation -> {
            TransactionFlag flag = invocation.getArgument(0);
            flag.setId(1L);
            return flag;
        });

        ScoringResult result = service.score(transaction);

        // ruleScore = duplicate(25) + velocity(20) + policy(20) = 65; AI disagrees with 5. Under
        // the old 50/50-blend formula this would have been round(0.5*65 + 0.5*5) = 35, NOT
        // flagged - this test pins the max() behavior that replaced that blend.
        assertThat(result.flagged()).isTrue();
        ArgumentCaptor<TransactionFlag> captor = ArgumentCaptor.forClass(TransactionFlag.class);
        verify(transactionFlagRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleScore()).isEqualTo(65);
        assertThat(captor.getValue().getAiRiskScore()).isEqualTo(5);
        assertThat(captor.getValue().getFinalScore()).isEqualTo(65);
    }

    private Transaction transactionAgainst(Merchant target) {
        return Transaction.builder().id(1L).cardId(1L).merchantId(target.getId())
                .amount(new BigDecimal("50.00")).currency("USD").occurredAt(now).country("US").build();
    }

    private void stubCommonLookups(List<Transaction> otherTransactions) {
        when(cardRepository.findById(1L)).thenReturn(Optional.of(card));
        when(employeeRepository.findById(10L)).thenReturn(Optional.of(employee));
        when(companyRepository.findById(100L)).thenReturn(Optional.of(company));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(transactionRepository.findByCardIdInAndIdNot(List.of(1L), 1L)).thenReturn(otherTransactions);
        when(merchantRepository.findAllById(any())).thenReturn(List.of(merchant, otherMerchant));
    }
}
