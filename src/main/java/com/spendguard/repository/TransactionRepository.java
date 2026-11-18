package com.spendguard.repository;

import java.time.Instant;
import java.util.List;

import com.spendguard.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** All transactions on any of these cards, excluding one transaction id (used to fetch "the rest of history"). */
    List<Transaction> findByCardIdInAndIdNot(List<Long> cardIds, Long excludedId);

    List<Transaction> findByCardIdInOrderByOccurredAtDesc(List<Long> cardIds);

    /**
     * True if this employee (identified by their card ids) has ever transacted with this merchant
     * strictly before the given time - same backward-looking-only discipline as the rule engine's
     * own history filtering, so "first time with this merchant" never leaks future transactions.
     */
    boolean existsByCardIdInAndMerchantIdAndOccurredAtBefore(List<Long> cardIds, Long merchantId, Instant occurredAt);
}
