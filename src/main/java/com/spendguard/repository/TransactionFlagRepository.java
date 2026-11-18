package com.spendguard.repository;

import java.util.List;
import java.util.Optional;

import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.TransactionFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionFlagRepository extends JpaRepository<TransactionFlag, Long> {

    Optional<TransactionFlag> findByTransactionId(Long transactionId);

    List<TransactionFlag> findByStatusOrderByFinalScoreDesc(FlagStatus status);
}
