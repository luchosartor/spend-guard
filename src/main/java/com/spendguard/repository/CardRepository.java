package com.spendguard.repository;

import java.util.List;

import com.spendguard.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, Long> {

    List<Card> findByEmployeeId(Long employeeId);
}
