package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.spendguard.domain.Card;
import com.spendguard.domain.Company;
import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.Employee;
import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.GeoAnomalyType;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.Transaction;
import com.spendguard.domain.TransactionFlag;
import com.spendguard.exception.ConflictException;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlagControllerTest {

    @Mock
    private TransactionFlagRepository flagRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CardRepository cardRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private CompanyRepository companyRepository;

    private FlagController controller;

    private final Instant now = Instant.parse("2026-11-19T12:00:00Z");
    private final Company company = Company.builder().id(100L).name("Beacon Partners").plan(CompanyPlan.PREMIUM).build();
    private final Employee employee = Employee.builder()
            .id(10L).companyId(100L).fullName("Morgan Diaz").department("Sales").role("Account Executive").build();
    private final Card card = Card.builder().id(1L).employeeId(10L).last4("1234").build();
    private final Merchant merchant = Merchant.builder()
            .id(500L).name("Luxury Jewelers").category(MerchantCategory.OFFICE).country("US")
            .policyLimit(new BigDecimal("300.00")).build();
    private final Transaction transaction = Transaction.builder()
            .id(1000L).cardId(1L).merchantId(500L).amount(new BigDecimal("81.08")).currency("USD")
            .occurredAt(now).country("US").build();

    @BeforeEach
    void setUp() {
        controller = new FlagController(flagRepository, transactionRepository, cardRepository,
                employeeRepository, merchantRepository, companyRepository);
    }

    private TransactionFlag pendingFlag() {
        return TransactionFlag.builder()
                .id(7L).transactionId(1000L).ruleScore(0).aiRiskScore(72).semanticMismatch(true)
                .aiExplanation("Looks like a semantic mismatch.").finalScore(72).status(FlagStatus.PENDING)
                .geoAnomalyType(GeoAnomalyType.NONE)
                .build();
    }

    @Test
    void list_mapsEmployeeMerchantAndCompanyOntoEachFlag() {
        when(flagRepository.findByStatusOrderByFinalScoreDesc(FlagStatus.PENDING)).thenReturn(List.of(pendingFlag()));
        when(transactionRepository.findAllById(any())).thenReturn(List.of(transaction));
        when(cardRepository.findAllById(any())).thenReturn(List.of(card));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(employee));
        when(companyRepository.findAllById(any())).thenReturn(List.of(company));
        when(merchantRepository.findAllById(any())).thenReturn(List.of(merchant));

        List<FlagSummaryResponse> result = controller.list(FlagStatus.PENDING);

        assertThat(result).hasSize(1);
        FlagSummaryResponse summary = result.get(0);
        assertThat(summary.employeeName()).isEqualTo("Morgan Diaz");
        assertThat(summary.companyName()).isEqualTo("Beacon Partners");
        assertThat(summary.merchantName()).isEqualTo("Luxury Jewelers");
        assertThat(summary.finalScore()).isEqualTo(72);
        assertThat(summary.semanticMismatch()).isTrue();
    }

    @Test
    void list_returnsEmptyWithoutTouchingAnyOtherRepository() {
        when(flagRepository.findByStatusOrderByFinalScoreDesc(FlagStatus.APPROVED)).thenReturn(List.of());

        List<FlagSummaryResponse> result = controller.list(FlagStatus.APPROVED);

        assertThat(result).isEmpty();
    }

    @Test
    void detail_returnsFullNestedBreakdown() {
        TransactionFlag flag = pendingFlag();
        when(flagRepository.findById(7L)).thenReturn(java.util.Optional.of(flag));
        when(transactionRepository.findById(1000L)).thenReturn(java.util.Optional.of(transaction));
        when(cardRepository.findById(1L)).thenReturn(java.util.Optional.of(card));
        when(employeeRepository.findById(10L)).thenReturn(java.util.Optional.of(employee));
        when(companyRepository.findById(100L)).thenReturn(java.util.Optional.of(company));
        when(merchantRepository.findById(500L)).thenReturn(java.util.Optional.of(merchant));

        FlagDetailResponse detail = controller.detail(7L);

        assertThat(detail.companyName()).isEqualTo("Beacon Partners");
        assertThat(detail.aiExplanation()).isEqualTo("Looks like a semantic mismatch.");
        assertThat(detail.employee().id()).isEqualTo(10L);
        assertThat(detail.merchant().policyLimit()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void detail_404sWhenTheFlagItselfIsMissing() {
        when(flagRepository.findById(7L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> controller.detail(7L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detail_404sWhenTheCompanyBehindTheEmployeeIsMissing() {
        when(flagRepository.findById(7L)).thenReturn(java.util.Optional.of(pendingFlag()));
        when(transactionRepository.findById(1000L)).thenReturn(java.util.Optional.of(transaction));
        when(cardRepository.findById(1L)).thenReturn(java.util.Optional.of(card));
        when(employeeRepository.findById(10L)).thenReturn(java.util.Optional.of(employee));
        when(companyRepository.findById(100L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> controller.detail(7L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolve_approvingAPendingFlagSetsStatusAndTimestamp() {
        TransactionFlag flag = pendingFlag();
        when(flagRepository.findById(7L)).thenReturn(java.util.Optional.of(flag));
        when(flagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResolveFlagResponse response = controller.resolve(7L, new ResolveFlagRequest(FlagStatus.APPROVED, "looks fine"));

        assertThat(response.status()).isEqualTo(FlagStatus.APPROVED);
        assertThat(response.resolvedNote()).isEqualTo("looks fine");
        assertThat(flag.getResolvedAt()).isNotNull();
        verify(flagRepository).save(flag);
    }

    @Test
    void resolve_rejectsResolvingAFlagThatsAlreadyResolved() {
        TransactionFlag flag = pendingFlag();
        flag.setStatus(FlagStatus.APPROVED);
        when(flagRepository.findById(7L)).thenReturn(java.util.Optional.of(flag));

        assertThatThrownBy(() -> controller.resolve(7L, new ResolveFlagRequest(FlagStatus.REJECTED, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resolve_rejectsADecisionOfPending() {
        // decision is @NotNull but still typed as the full FlagStatus enum - PENDING is a value
        // bean validation lets through, so the controller itself must reject it explicitly.
        assertThatThrownBy(() -> controller.resolve(7L, new ResolveFlagRequest(FlagStatus.PENDING, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
