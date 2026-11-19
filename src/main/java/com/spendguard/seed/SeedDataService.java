package com.spendguard.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.spendguard.ai.AiMode;
import com.spendguard.ai.ApiFraudAiReviewer;
import com.spendguard.ai.CliFraudAiReviewer;
import com.spendguard.ai.FraudAiReviewer;
import com.spendguard.ai.NoOpFraudAiReviewer;
import com.spendguard.domain.Card;
import com.spendguard.domain.Company;
import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.Employee;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.PlantedType;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import com.spendguard.web.IngestResponse;
import com.spendguard.web.TransactionIngestService;
import com.spendguard.web.TransactionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Synthetic data generator behind {@code POST /api/admin/seed}. Demo-only: wipes existing data and
 * regenerates two companies (one STANDARD, one PREMIUM), each with a single employee and a small,
 * deterministic history (6 SOFTWARE + 6 OFFICE transactions - enough to clear the rule engine's
 * 5-prior-transaction minimum for a z-score), then plants 3 anomaly types on that employee:
 * {@link PlantedType#AMOUNT_OUTLIER}, {@link PlantedType#POLICY}, and
 * {@link PlantedType#SEMANTIC_MISMATCH}. 30 ingest calls total (12 history + 3 planted, x2
 * companies) - small and cheap enough to run against a real AI provider without it taking several
 * minutes or dollars. {@link PlantedType#VELOCITY}, {@link PlantedType#DUPLICATE} and
 * {@link PlantedType#GEO} are still fully supported by the rule engine itself (see
 * {@code RuleEngineTest}) - they're just not planted by this generator right now.
 *
 * <p>Every transaction, normal or planted, goes through {@link TransactionIngestService#ingest}
 * exactly like the public API would - nothing here talks to the scoring pipeline directly. Each
 * call to {@code ingest()} is its own, independently-committed transaction (see
 * {@code TransactionIngestService}); {@code seed()} itself is deliberately NOT wrapped in one big
 * transaction, since novelty-escalation means several of those ingest calls (first contact with a
 * merchant) may call out to the AI reviewer, each potentially blocking for seconds - holding one DB
 * connection/transaction open for that whole run would be its own problem.
 */
@Service
@RequiredArgsConstructor
public class SeedDataService {

    /**
     * One employee per company (2 total) - drawn without replacement across the whole run (see
     * {@code seed()}) from a pool bigger than needed, so the two companies' employees never land
     * on the same name. Without that, both companies reusing the same fixed list by position would
     * give them the identical name, which looks like one person flagged twice in the queue when
     * they're actually two different {@code Employee} rows.
     */
    private static final String[] EMPLOYEE_NAMES = {
            "Jordan Lee", "Taylor Chen", "Morgan Diaz", "Casey Kim",
            "Riley Patel", "Avery Brooks", "Hayden Reyes", "Quinn Walsh"
    };
    private static final String DEPARTMENT = "Sales";
    private static final String ROLE = "Account Executive";

    /**
     * category -> {minMean, maxMean, stddev as a fraction of mean} - each employee gets their own
     * draw from this. An {@link EnumMap} (not {@code Map.of(...)}) specifically because its
     * iteration order is the enum's fixed declaration order - a hash-based Map's iteration order is
     * randomized per JVM run, which would silently break the fixed {@code Random(42)} seed's
     * reproducibility below (the same seed would draw baselines in a different order each restart).
     */
    private static final Map<MerchantCategory, double[]> BASELINE_RANGES = createBaselineRanges();

    private static Map<MerchantCategory, double[]> createBaselineRanges() {
        Map<MerchantCategory, double[]> ranges = new EnumMap<>(MerchantCategory.class);
        ranges.put(MerchantCategory.OFFICE, new double[]{60, 200, 0.20});
        ranges.put(MerchantCategory.SOFTWARE, new double[]{80, 350, 0.15});
        return ranges;
    }

    // One per category (SOFTWARE, OFFICE) - clears the rule engine's 5-prior-transaction minimum
    // for a z-score with room to spare, without padding the run with history nothing else needs.
    private static final int HISTORY_TRANSACTIONS_PER_CATEGORY = 6;
    private static final int RANDOM_SEED = 42;

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final MerchantRepository merchantRepository;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionFlagRepository transactionFlagRepository;
    private final TransactionIngestService ingestService;
    private final ApiFraudAiReviewer apiFraudAiReviewer;
    private final CliFraudAiReviewer cliFraudAiReviewer;
    private final NoOpFraudAiReviewer noOpFraudAiReviewer;

    /**
     * Not atomic and not safe to call concurrently: each {@code ingest()} call below commits its
     * own transaction independently (see {@link TransactionIngestService}), so a run that's
     * interrupted partway through leaves a partially-regenerated dataset rather than rolling back
     * cleanly. Acceptable for this demo-only, single-operator endpoint.
     *
     * @param mode which {@link FraudAiReviewer} every escalation in this run uses - picked per run
     *             by the seed endpoint's caller, independent of {@code spendguard.ai.provider}.
     */
    public SeedRunResult seed(AiMode mode) {
        FraudAiReviewer reviewer = switch (mode) {
            case API -> apiFraudAiReviewer;
            case CLI -> cliFraudAiReviewer;
            case NONE -> noOpFraudAiReviewer;
        };
        wipeAll();

        Random random = new Random(RANDOM_SEED);
        List<String> namePool = new ArrayList<>(Arrays.asList(EMPLOYEE_NAMES));
        Collections.shuffle(namePool, random);
        Map<MerchantCategory, Merchant> normalMerchants = createNormalMerchants();
        Merchant semanticMismatchMerchant = createSemanticMismatchMerchant();
        int merchantsCreated = normalMerchants.size() + 1;

        Company standardCompany = companyRepository.save(
                Company.builder().name("Northwind Industries").plan(CompanyPlan.STANDARD).build());
        Company premiumCompany = companyRepository.save(
                Company.builder().name("Beacon Partners").plan(CompanyPlan.PREMIUM).build());
        List<Company> companies = List.of(standardCompany, premiumCompany);

        Instant now = Instant.now();
        // Normal history ends a week before "now"; planted anomalies live in that most-recent week so
        // they never overlap with the bulk history's own timestamps.
        Instant historyEnd = now.minus(Duration.ofDays(7));

        int employeesCreated = 0;
        List<PlantedAnomalyOutcome> plantedOutcomes = new ArrayList<>();

        for (Company company : companies) {
            Employee employee = createEmployee(company, namePool, random);
            employeesCreated++;

            Map<MerchantCategory, CategoryBaseline> baseline = randomBaseline(random);
            generateHistory(employee, baseline, normalMerchants, historyEnd, random, reviewer);

            plantedOutcomes.addAll(plantAnomalies(
                    employee, baseline, normalMerchants, semanticMismatchMerchant, company.getPlan(), now, reviewer));
        }

        int totalTransactions = (int) transactionRepository.count();
        int totalFlags = (int) transactionFlagRepository.count();

        return new SeedRunResult(companies.size(), employeesCreated, merchantsCreated, totalTransactions, totalFlags, plantedOutcomes);
    }

    private void wipeAll() {
        transactionFlagRepository.deleteAll();
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
        employeeRepository.deleteAll();
        merchantRepository.deleteAll();
        companyRepository.deleteAll();
    }

    private Map<MerchantCategory, Merchant> createNormalMerchants() {
        Map<MerchantCategory, Merchant> merchants = new LinkedHashMap<>();
        merchants.put(MerchantCategory.OFFICE, saveMerchant("QuickMart Office Supplies", MerchantCategory.OFFICE, "US", "300"));
        merchants.put(MerchantCategory.SOFTWARE, saveMerchant("CloudStack Software", MerchantCategory.SOFTWARE, "US", "500"));
        return merchants;
    }

    /** Deliberately mismatched: a luxury jeweler categorized as office supplies - reserved for the SEMANTIC_MISMATCH plant. */
    private Merchant createSemanticMismatchMerchant() {
        return saveMerchant("Luxury Jewelers", MerchantCategory.OFFICE, "US", "300");
    }

    private Merchant saveMerchant(String name, MerchantCategory category, String country, String policyLimit) {
        return merchantRepository.save(Merchant.builder()
                .name(name)
                .category(category)
                .country(country)
                .policyLimit(new BigDecimal(policyLimit))
                .build());
    }

    private Employee createEmployee(Company company, List<String> namePool, Random random) {
        // Drawn from the front of the shared, pre-shuffled pool - removing as we go, so the two
        // companies' employees never end up with the same name.
        String name = namePool.remove(0);
        Employee employee = employeeRepository.save(Employee.builder()
                .companyId(company.getId())
                .fullName(name)
                .department(DEPARTMENT)
                .role(ROLE)
                .build());
        cardRepository.save(Card.builder()
                .employeeId(employee.getId())
                .last4("%04d".formatted(random.nextInt(10000)))
                .build());
        return employee;
    }

    private Map<MerchantCategory, CategoryBaseline> randomBaseline(Random random) {
        Map<MerchantCategory, CategoryBaseline> baseline = new HashMap<>();
        for (Map.Entry<MerchantCategory, double[]> entry : BASELINE_RANGES.entrySet()) {
            double[] range = entry.getValue();
            double mean = range[0] + random.nextDouble() * (range[1] - range[0]);
            double stddev = mean * range[2];
            baseline.put(entry.getKey(), new CategoryBaseline(mean, stddev));
        }
        return baseline;
    }

    /**
     * {@link #HISTORY_TRANSACTIONS_PER_CATEGORY} transactions in each of SOFTWARE and OFFICE,
     * spaced two days apart and ending at {@code historyEnd} - enough same-category history for
     * the rule engine's z-score (needs 5+ prior) without generating a single transaction more than
     * that for a category nothing else here needs.
     */
    private void generateHistory(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                   Map<MerchantCategory, Merchant> normalMerchants,
                                   Instant historyEnd, Random random, FraudAiReviewer reviewer) {
        for (MerchantCategory category : normalMerchants.keySet()) {
            Merchant merchant = normalMerchants.get(category);
            CategoryBaseline categoryBaseline = baseline.get(category);
            for (int i = 0; i < HISTORY_TRANSACTIONS_PER_CATEGORY; i++) {
                int daysBeforeEnd = 2 * (HISTORY_TRANSACTIONS_PER_CATEGORY - i);
                Instant occurredAt = historyEnd.minus(Duration.ofDays(daysBeforeEnd)).plus(Duration.ofHours(9 + random.nextInt(8)));
                BigDecimal amount = randomAmount(categoryBaseline, random);

                ingestService.ingest(new TransactionRequest(
                        employee.getId(), merchant.getId(), amount, "USD", occurredAt, merchant.getCountry()), reviewer);
            }
        }
    }

    private BigDecimal randomAmount(CategoryBaseline baseline, Random random) {
        double raw = baseline.mean() + random.nextGaussian() * baseline.stddev();
        double clamped = Math.max(baseline.mean() * 0.1, raw);
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * One of each planted type, built around a single "anomaly employee" so every scenario has
     * enough real history to sit on top of. Each uses the single ingest() path; some need a couple
     * of untagged setup transactions immediately before them to create the pattern (e.g. velocity
     * needs 3 transactions in 10 minutes) - only the one transaction that completes the pattern is
     * tagged as the planted anomaly.
     */
    private List<PlantedAnomalyOutcome> plantAnomalies(
            Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
            Map<MerchantCategory, Merchant> normalMerchants, Merchant semanticMismatchMerchant,
            CompanyPlan plan, Instant now, FraudAiReviewer reviewer) {

        Instant t0 = now.minus(Duration.ofDays(5));
        List<PlantedAnomalyOutcome> outcomes = new ArrayList<>();
        outcomes.add(plantAmountOutlier(employee, baseline, normalMerchants, plan, t0, reviewer));
        outcomes.add(plantPolicy(employee, normalMerchants, plan, t0.plus(Duration.ofHours(6)), reviewer));
        outcomes.add(plantSemanticMismatch(employee, baseline, semanticMismatchMerchant, plan, t0.plus(Duration.ofHours(12)), reviewer));
        return outcomes;
    }

    /**
     * Amount far outside the employee's own category baseline (z >= 4), everything else normal.
     * ruleScore alone is 30 (or more if it also happens to clear the merchant's policy limit) -
     * clears both the STANDARD (20) and PREMIUM (10) escalation thresholds on its own; the merchant
     * is familiar (used throughout normal history), so escalation here is purely threshold-driven.
     */
    private PlantedAnomalyOutcome plantAmountOutlier(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                                       Map<MerchantCategory, Merchant> normalMerchants, CompanyPlan plan, Instant at,
                                                       FraudAiReviewer reviewer) {
        MerchantCategory category = MerchantCategory.SOFTWARE;
        Merchant merchant = normalMerchants.get(category);
        CategoryBaseline categoryBaseline = baseline.get(category);
        BigDecimal amount = BigDecimal.valueOf(categoryBaseline.mean() + 6 * categoryBaseline.stddev())
                .setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD", at, merchant.getCountry()),
                true, PlantedType.AMOUNT_OUTLIER, reviewer);
        return outcomeFor(response, PlantedType.AMOUNT_OUTLIER, plan);
    }

    /**
     * Amount comfortably over the merchant's policy limit; everything else normal. ruleScore is at
     * least 20 from the policy violation alone (usually more, since 1.5x the limit is also
     * typically a z-score outlier against the employee's own baseline) - clears both thresholds;
     * familiar merchant, so this is threshold-driven, not novelty-driven.
     */
    private PlantedAnomalyOutcome plantPolicy(Employee employee, Map<MerchantCategory, Merchant> normalMerchants,
                                                CompanyPlan plan, Instant at, FraudAiReviewer reviewer) {
        Merchant merchant = normalMerchants.get(MerchantCategory.OFFICE);
        BigDecimal amount = merchant.getPolicyLimit().multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD", at, merchant.getCountry()),
                true, PlantedType.POLICY, reviewer);
        return outcomeFor(response, PlantedType.POLICY, plan);
    }

    /**
     * Amount/velocity/geo/policy all look completely normal (same category baseline, familiar
     * country, isolated in time) - only the merchant/category pairing is wrong, which no numeric
     * signal can see. ruleScore here is ~0, so this plant only ever reaches the AI reviewer via the
     * Fix-1 "first time with this merchant" novelty path, never via the rule-score threshold.
     * Expect {@code caught: false} in the seed response unless a live AI provider is actually
     * configured (ANTHROPIC_API_KEY for the API provider, or a working `claude` CLI): with AI
     * unavailable, scoring escalates (novelty fires) but then falls back to the rule-only score
     * (~0), which never clears the finalScore >= 50 flag line. That fallback-to-zero outcome is the
     * expected, correct behavior of this exact scenario without AI - it's not a bug in detection.
     */
    private PlantedAnomalyOutcome plantSemanticMismatch(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                                          Merchant semanticMismatchMerchant, CompanyPlan plan, Instant at,
                                                          FraudAiReviewer reviewer) {
        CategoryBaseline officeBaseline = baseline.get(MerchantCategory.OFFICE);
        BigDecimal amount = BigDecimal.valueOf(officeBaseline.mean()).setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), semanticMismatchMerchant.getId(), amount, "USD",
                        at, semanticMismatchMerchant.getCountry()),
                true, PlantedType.SEMANTIC_MISMATCH, reviewer);
        return outcomeFor(response, PlantedType.SEMANTIC_MISMATCH, plan);
    }

    private PlantedAnomalyOutcome outcomeFor(IngestResponse response, PlantedType type, CompanyPlan plan) {
        return new PlantedAnomalyOutcome(response.transactionId(), type, plan, response.flagged());
    }

    private record CategoryBaseline(double mean, double stddev) {
    }
}
