package com.spendguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Explicit column name required: without it, Hibernate derives "company_id" from the field
    // name AND the @JoinColumn below also declares "company_id" as ITS logical name for the same
    // physical column - two different logical names for one physical column throws
    // DuplicateMappingException at context startup. Naming both explicitly the same way resolves it.
    @Column(name = "company_id")
    private Long companyId;

    /**
     * Read-only mirror of {@link #companyId} purely so {@code ddl-auto=update} generates a real
     * foreign key constraint on the column - the rest of the codebase reads/writes the plain
     * {@code companyId} field, never this relation, so it's never fetched.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    private String fullName;

    private String department;

    private String role;
}
