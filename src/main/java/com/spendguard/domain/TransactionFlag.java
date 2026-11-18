package com.spendguard.domain;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transaction_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Explicit column name so it matches the @JoinColumn below exactly - see Employee.companyId
    // for why two different logical names on one physical column breaks Hibernate startup.
    /** One flag per transaction: only created once the combined score clears the review-queue threshold. */
    @Column(name = "transaction_id", nullable = false, unique = true)
    private Long transactionId;

    /** Read-only mirror of {@link #transactionId} - see {@link Employee#getCompany()} for why. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", insertable = false, updatable = false)
    private Transaction transaction;

    private int ruleScore;

    /**
     * Rule signal breakdown, persisted at scoring time rather than recomputed live on read.
     * Reasoning: nothing orders ingestion by {@code occurredAt} (a late-arriving, earlier-dated
     * transaction can land after a later one has already been scored), so recomputing the
     * breakdown on demand could drift from the {@code ruleScore}/{@code aiExplanation} that were
     * actually used to create this flag - always show the analyst what was actually scored with.
     */
    @Column(name = "amount_z_score")
    private Double amountZScore;

    private boolean velocityTriggered;

    private boolean duplicateTriggered;

    @Enumerated(EnumType.STRING)
    private GeoAnomalyType geoAnomalyType;

    private boolean policyViolation;

    /** Null if this transaction was never escalated to the AI reviewer. */
    private Integer aiRiskScore;

    /** Null if never escalated to AI. */
    private Boolean semanticMismatch;

    /** Null if never escalated to AI. */
    @Column(columnDefinition = "TEXT")
    private String aiExplanation;

    private int finalScore;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FlagStatus status = FlagStatus.PENDING;

    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String resolvedNote;
}
