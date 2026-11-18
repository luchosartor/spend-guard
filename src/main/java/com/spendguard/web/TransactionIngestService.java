package com.spendguard.web;

import com.spendguard.detection.ScoringResult;
import com.spendguard.detection.TransactionScoringService;
import com.spendguard.domain.Card;
import com.spendguard.domain.PlantedType;
import com.spendguard.domain.Transaction;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single path every transaction goes through, real or synthetic: persist it, then synchronously
 * score it. Nothing is ever pre-labeled before scoring - the planted-anomaly tags accepted here are
 * purely descriptive metadata the seed generator attaches for later recall measurement; the rule
 * engine and AI reviewer never see them.
 *
 * <p>Both public overloads below are annotated {@code @Transactional} directly and contain the full
 * persist+score logic themselves (no delegating to each other via {@code this.}) - a self-invoked
 * call bypasses Spring's transactional proxy entirely, so a prior version of this class that had
 * {@code ingest(request)} call {@code this.ingest(request, false, null)} ran with no transaction
 * boundary at all on the real API path.
 */
@Service
@RequiredArgsConstructor
public class TransactionIngestService {

    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionScoringService scoringService;

    @Transactional
    public IngestResponse ingest(TransactionRequest request) {
        return doIngest(request, false, null);
    }

    /** Used by the seed generator to tag a planted anomaly; the real endpoint always calls {@link #ingest(TransactionRequest)}. */
    @Transactional
    public IngestResponse ingest(TransactionRequest request, boolean plantedAnomaly, PlantedType plantedType) {
        return doIngest(request, plantedAnomaly, plantedType);
    }

    private IngestResponse doIngest(TransactionRequest request, boolean plantedAnomaly, PlantedType plantedType) {
        Card card = cardRepository.findByEmployeeId(request.employeeId()).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No card found for employee " + request.employeeId()));

        // Validate the merchant exists BEFORE writing anything - a bad merchant_id must never
        // leave a permanent, un-scoreable transaction row behind (every later ingest for this
        // employee would NPE trying to resolve that row's merchant category out of history).
        merchantRepository.findById(request.merchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + request.merchantId()));

        String currency = (request.currency() == null || request.currency().isBlank()) ? "USD" : request.currency();

        Transaction transaction = Transaction.builder()
                .cardId(card.getId())
                .merchantId(request.merchantId())
                .amount(request.amount())
                .currency(currency)
                .occurredAt(request.occurredAt())
                .country(request.country())
                .plantedAnomaly(plantedAnomaly)
                .plantedType(plantedType)
                .build();
        transaction = transactionRepository.save(transaction);

        ScoringResult result = scoringService.score(transaction);
        return new IngestResponse(transaction.getId(), result.flagged(), result.flagId());
    }
}
