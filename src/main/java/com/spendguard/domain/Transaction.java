package com.spendguard.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Explicit column names so each matches its sibling @JoinColumn below exactly - see
    // Employee.companyId for why two different logical names on one physical column breaks
    // Hibernate startup.
    @Column(name = "card_id")
    private Long cardId;

    /** Read-only mirror of {@link #cardId} - see {@link Employee#getCompany()} for why. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id", insertable = false, updatable = false)
    private Card card;

    @Column(name = "merchant_id")
    private Long merchantId;

    /** Read-only mirror of {@link #merchantId} - see {@link Employee#getCompany()} for why. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    private Merchant merchant;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    private String currency = "USD";

    private Instant occurredAt;

    /** May differ from the merchant's home country (card-not-present / travel). */
    private String country;

    /**
     * Internal only, planted by the synthetic data generator so detection recall can be measured.
     * Never serialized to analyst-facing DTOs.
     */
    @Builder.Default
    private boolean plantedAnomaly = false;

    /** Internal only, see {@link #plantedAnomaly}. Null unless plantedAnomaly is true. */
    @Enumerated(EnumType.STRING)
    private PlantedType plantedType;
}
