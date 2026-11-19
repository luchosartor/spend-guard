package com.spendguard.seed;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.spendguard.SpendGuardApplication;
import com.spendguard.ai.AiMode;
import com.spendguard.domain.Employee;
import com.spendguard.domain.PlantedType;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context against H2 (same approach as {@code JpaMappingSmokeTest}) and
 * runs the actual seed generator end to end with {@link AiMode#NONE} - free, no network calls,
 * CI-safe - instead of mocking out the ~10 collaborators {@link SeedDataService} has (repositories,
 * {@code TransactionIngestService}, the three {@code FraudAiReviewer} beans). This exercises the
 * real ingest path and real persistence, which is what actually matters here: the three remaining
 * plant types land, the per-run size stays what it was redesigned to be, and the two companies'
 * employees never collide on a name (see the real incident this was built to prevent).
 */
@SpringBootTest(classes = SpendGuardApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:spendguard-seed-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SeedDataServiceTest {

    @Autowired
    private SeedDataService seedDataService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private CompanyRepository companyRepository;

    @Test
    void seedWithNoneMode_createsThirtyTransactionsAndSixPlants() {
        SeedRunResult result = seedDataService.seed(AiMode.NONE);

        assertThat(result.companiesCreated()).isEqualTo(2);
        assertThat(result.employeesCreated()).isEqualTo(2);
        assertThat(result.merchantsCreated()).isEqualTo(3);
        assertThat(result.totalTransactions()).isEqualTo(30);
        assertThat(transactionRepository.count()).isEqualTo(30);
        assertThat(result.plantedAnomalies()).hasSize(6);
    }

    @Test
    void seedWithNoneMode_plantsExactlyTheThreeDocumentedTypesPerCompany() {
        SeedRunResult result = seedDataService.seed(AiMode.NONE);

        Set<PlantedType> types = result.plantedAnomalies().stream()
                .map(PlantedAnomalyOutcome::plantedType)
                .collect(Collectors.toSet());

        assertThat(types).containsExactlyInAnyOrder(
                PlantedType.AMOUNT_OUTLIER, PlantedType.POLICY, PlantedType.SEMANTIC_MISMATCH);
    }

    @Test
    void seedWithNoneMode_theTwoCompaniesNeverShareAnEmployeeName() {
        // Regression: both companies used to draw employee #0 from the same fixed-by-position
        // list, so they always got the identical name - two different Employee rows looked like
        // one person flagged twice in the queue.
        seedDataService.seed(AiMode.NONE);

        List<Employee> employees = employeeRepository.findAll();
        assertThat(employees).hasSize(2);
        assertThat(employees.get(0).getFullName()).isNotEqualTo(employees.get(1).getFullName());
    }

    @Test
    void seedWithNoneMode_runningTwiceWipesThePreviousRunCleanly() {
        seedDataService.seed(AiMode.NONE);

        SeedRunResult second = seedDataService.seed(AiMode.NONE);

        assertThat(second.totalTransactions()).isEqualTo(30);
        assertThat(transactionRepository.count()).isEqualTo(30);
        assertThat(companyRepository.count()).isEqualTo(2);
    }
}
