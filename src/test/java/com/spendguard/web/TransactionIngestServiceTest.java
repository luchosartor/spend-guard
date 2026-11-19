package com.spendguard.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.spendguard.ai.FraudAiReviewer;
import com.spendguard.detection.ScoringResult;
import com.spendguard.detection.TransactionScoringService;
import com.spendguard.domain.Card;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.PlantedType;
import com.spendguard.domain.Transaction;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionIngestServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CardRepository cardRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private TransactionScoringService scoringService;
    @Mock
    private FraudAiReviewer reviewerOverride;

    private TransactionIngestService ingestService;

    private final Card card = Card.builder().id(1L).employeeId(10L).last4("1234").build();
    private final Merchant merchant = Merchant.builder()
            .id(500L).name("CloudStack Software").category(MerchantCategory.SOFTWARE).country("US")
            .policyLimit(new BigDecimal("500.00")).build();
    private final Instant occurredAt = Instant.parse("2026-11-19T12:00:00Z");

    @BeforeEach
    void setUp() {
        ingestService = new TransactionIngestService(transactionRepository, cardRepository, merchantRepository, scoringService);
    }

    private TransactionRequest request() {
        return new TransactionRequest(10L, 500L, new BigDecimal("42.00"), "USD", occurredAt, "US");
    }

    @Test
    void ingest_persistsThenScoresAndReturnsWhetherItFlagged() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(999L);
            return t;
        });
        when(scoringService.score(any(), isNull())).thenReturn(new ScoringResult(true, 55L));

        IngestResponse response = ingestService.ingest(request());

        assertThat(response.transactionId()).isEqualTo(999L);
        assertThat(response.flagged()).isTrue();
        assertThat(response.flagId()).isEqualTo(55L);
    }

    @Test
    void ingest_throwsWhenTheEmployeeHasNoCard() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> ingestService.ingest(request())).isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void ingest_validatesTheMerchantExistsBeforeWritingAnyTransactionRow() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestService.ingest(request())).isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).save(any());
        verify(scoringService, never()).score(any(), any());
    }

    @Test
    void ingest_defaultsBlankCurrencyToUsd() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        when(transactionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringService.score(any(), isNull())).thenReturn(new ScoringResult(false, null));

        ingestService.ingest(new TransactionRequest(10L, 500L, new BigDecimal("42.00"), "  ", occurredAt, "US"));

        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void ingest_taggedOverload_marksThePlantedAnomalyFields() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        when(transactionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringService.score(any(), isNull())).thenReturn(new ScoringResult(true, 1L));

        ingestService.ingest(request(), true, PlantedType.AMOUNT_OUTLIER);

        assertThat(captor.getValue().isPlantedAnomaly()).isTrue();
        assertThat(captor.getValue().getPlantedType()).isEqualTo(PlantedType.AMOUNT_OUTLIER);
    }

    @Test
    void ingest_withReviewerOverride_passesItThroughToScoring() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringService.score(any(), same(reviewerOverride))).thenReturn(new ScoringResult(false, null));

        ingestService.ingest(request(), reviewerOverride);

        verify(scoringService).score(any(), same(reviewerOverride));
    }

    @Test
    void ingest_taggedOverloadWithReviewerOverride_passesBothThrough() {
        when(cardRepository.findByEmployeeId(10L)).thenReturn(List.of(card));
        when(merchantRepository.findById(500L)).thenReturn(Optional.of(merchant));
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        when(transactionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(scoringService.score(any(), same(reviewerOverride))).thenReturn(new ScoringResult(true, 2L));

        ingestService.ingest(request(), true, PlantedType.POLICY, reviewerOverride);

        assertThat(captor.getValue().getPlantedType()).isEqualTo(PlantedType.POLICY);
        verify(scoringService).score(any(), same(reviewerOverride));
    }
}
