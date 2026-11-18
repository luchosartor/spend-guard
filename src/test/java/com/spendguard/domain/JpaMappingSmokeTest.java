package com.spendguard.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.spendguard.SpendGuardApplication;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring application context - JPA included - against an in-memory H2 database,
 * and round-trips one row through every entity.
 *
 * <p>This is the regression test for a real incident: a prior round added read-only
 * {@code @ManyToOne}/{@code @OneToOne} relations alongside the existing plain {@code Long} FK
 * fields (so {@code ddl-auto=update} would generate real foreign key constraints) without giving
 * each {@code Long} field and its sibling {@code @JoinColumn} the same explicit column name - two
 * different logical names for one physical column threw {@code DuplicateMappingException} at
 * context startup. Every other test in this suite is Mockito-only and never boots a real
 * {@code EntityManagerFactory}, so nothing else here would have caught that.
 *
 * <p>A plain {@code @SpringBootTest} is used instead of the (JPA-only) {@code @DataJpaTest} slice
 * because Spring Boot 4.1.1 no longer ships that annotation in {@code spring-boot-test-autoconfigure}
 * (it was split out/removed in the 4.0 module reorganization) - booting the whole context still
 * exercises the same Hibernate metadata build that failed, which is the only thing this test needs.
 */
@SpringBootTest(classes = SpendGuardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:spendguard-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class JpaMappingSmokeTest {

    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private CardRepository cardRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private TransactionFlagRepository transactionFlagRepository;

    @Test
    void persistsAndReadsBackOneRowOfEveryEntity() {
        Company company = companyRepository.save(
                Company.builder().name("Acme").plan(CompanyPlan.STANDARD).build());
        Employee employee = employeeRepository.save(Employee.builder()
                .companyId(company.getId()).fullName("Jordan Lee").department("Engineering").role("SWE").build());
        Card card = cardRepository.save(Card.builder().employeeId(employee.getId()).last4("1234").build());
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Corner Bistro").category(MerchantCategory.MEALS).country("US")
                .policyLimit(new BigDecimal("100.00")).build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .cardId(card.getId()).merchantId(merchant.getId()).amount(new BigDecimal("42.00"))
                .currency("USD").occurredAt(Instant.parse("2026-11-18T12:00:00Z")).country("US").build());
        TransactionFlag flag = transactionFlagRepository.save(TransactionFlag.builder()
                .transactionId(transaction.getId()).ruleScore(60).finalScore(60)
                .geoAnomalyType(GeoAnomalyType.NONE).build());

        assertThat(employeeRepository.findById(employee.getId()).orElseThrow().getCompanyId())
                .isEqualTo(company.getId());
        assertThat(cardRepository.findById(card.getId()).orElseThrow().getEmployeeId())
                .isEqualTo(employee.getId());
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getCardId())
                .isEqualTo(card.getId());
        assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getMerchantId())
                .isEqualTo(merchant.getId());
        assertThat(transactionFlagRepository.findById(flag.getId()).orElseThrow().getTransactionId())
                .isEqualTo(transaction.getId());
    }
}
