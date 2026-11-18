package com.spendguard.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
 * regenerates two companies (one STANDARD, one PREMIUM) with individually-baselined employees,
 * 3-4 months of plausible history, and a dozen planted anomalies - one of each {@link PlantedType}
 * per company, so the demo can show whether PREMIUM's lower threshold/stronger model catches
 * something STANDARD misses. Every type uses the exact same anomaly shape on both companies - in
 * particular GEO is the soft "first time in this country" variant on both, specifically so the
 * 20-vs-10 escalation threshold (not novelty, not a different scenario) is what decides whether it
 * even reaches the AI reviewer. See {@code plantGeoFirstInCountry} below.
 *
 * <p>Every transaction, normal or planted, goes through {@link TransactionIngestService#ingest}
 * exactly like the public API would - nothing here talks to the scoring pipeline directly. Each
 * call to {@code ingest()} is its own, independently-committed transaction (see
 * {@code TransactionIngestService}); {@code seed()} itself is deliberately NOT wrapped in one big
 * transaction, since novelty-escalation means roughly 100 of those ingest calls (every employee's
 * first contact with each merchant) may call out to the AI reviewer, each potentially blocking for
 * seconds - holding one DB connection/transaction open for that whole run would be its own problem.
 */
@Service
@RequiredArgsConstructor
public class SeedDataService {

    private static final String[] EMPLOYEE_NAMES = {
            "Jordan Lee", "Taylor Chen", "Morgan Diaz", "Casey Kim",
            "Riley Patel", "Avery Brooks", "Hayden Reyes", "Quinn Walsh"
    };
    private static final String[] DEPARTMENTS = {
            "Sales", "Engineering", "Marketing", "Finance",
            "Operations", "Customer Success", "Design", "Executive"
    };
    private static final String[] ROLES = {
            "Account Executive", "Software Engineer", "Marketing Manager", "Financial Analyst",
            "Ops Coordinator", "Support Lead", "Product Designer", "VP"
    };

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
        ranges.put(MerchantCategory.MEALS, new double[]{25, 60, 0.20});
        ranges.put(MerchantCategory.OFFICE, new double[]{60, 200, 0.20});
        ranges.put(MerchantCategory.SOFTWARE, new double[]{80, 350, 0.15});
        ranges.put(MerchantCategory.TRAVEL, new double[]{300, 1200, 0.25});
        ranges.put(MerchantCategory.TRANSPORT, new double[]{15, 80, 0.20});
        ranges.put(MerchantCategory.ENTERTAINMENT, new double[]{40, 150, 0.20});
        return ranges;
    }

    private static final int HISTORY_WEEKS = 15;
    private static final int RANDOM_SEED = 42;

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final MerchantRepository merchantRepository;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionFlagRepository transactionFlagRepository;
    private final TransactionIngestService ingestService;

    /**
     * Not atomic and not safe to call concurrently: each {@code ingest()} call below commits its
     * own transaction independently (see {@link TransactionIngestService}), so a run that's
     * interrupted partway through leaves a partially-regenerated dataset rather than rolling back
     * cleanly. Acceptable for this demo-only, single-operator endpoint.
     */
    public SeedRunResult seed() {
        wipeAll();

        Random random = new Random(RANDOM_SEED);
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
        Instant historyStart = historyEnd.minus(Duration.ofDays(7L * HISTORY_WEEKS));

        int employeesCreated = 0;
        List<PlantedAnomalyOutcome> plantedOutcomes = new ArrayList<>();

        for (Company company : companies) {
            List<Employee> employees = createEmployees(company, random);
            employeesCreated += employees.size();

            Map<Long, Map<MerchantCategory, CategoryBaseline>> baselinesByEmployee = new HashMap<>();
            for (Employee employee : employees) {
                Map<MerchantCategory, CategoryBaseline> baseline = randomBaseline(random);
                baselinesByEmployee.put(employee.getId(), baseline);
                generateHistory(employee, baseline, normalMerchants, historyStart, historyEnd, random);
            }

            Employee anomalyEmployee = employees.get(0);
            Map<MerchantCategory, CategoryBaseline> anomalyBaseline = baselinesByEmployee.get(anomalyEmployee.getId());
            plantedOutcomes.addAll(plantAnomalies(
                    anomalyEmployee, anomalyBaseline, normalMerchants, semanticMismatchMerchant, company.getPlan(), now));
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
        merchants.put(MerchantCategory.MEALS, saveMerchant("Corner Bistro", MerchantCategory.MEALS, "US", "100"));
        merchants.put(MerchantCategory.OFFICE, saveMerchant("QuickMart Office Supplies", MerchantCategory.OFFICE, "US", "300"));
        merchants.put(MerchantCategory.SOFTWARE, saveMerchant("CloudStack Software", MerchantCategory.SOFTWARE, "US", "500"));
        merchants.put(MerchantCategory.TRAVEL, saveMerchant("Globalink Airlines", MerchantCategory.TRAVEL, "US", "2000"));
        merchants.put(MerchantCategory.TRANSPORT, saveMerchant("MetroCab", MerchantCategory.TRANSPORT, "US", "150"));
        merchants.put(MerchantCategory.ENTERTAINMENT, saveMerchant("Cinematic Lounge", MerchantCategory.ENTERTAINMENT, "US", "200"));
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

    private List<Employee> createEmployees(Company company, Random random) {
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < EMPLOYEE_NAMES.length; i++) {
            Employee employee = employeeRepository.save(Employee.builder()
                    .companyId(company.getId())
                    .fullName(EMPLOYEE_NAMES[i])
                    .department(DEPARTMENTS[i])
                    .role(ROLES[i])
                    .build());
            cardRepository.save(Card.builder()
                    .employeeId(employee.getId())
                    .last4("%04d".formatted(random.nextInt(10000)))
                    .build());
            employees.add(employee);
        }
        return employees;
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

    /** A handful of normal transactions per week, drawn from the employee's own per-category baseline. */
    private void generateHistory(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                   Map<MerchantCategory, Merchant> normalMerchants,
                                   Instant historyStart, Instant historyEnd, Random random) {
        MerchantCategory[] categories = MerchantCategory.values();
        for (int week = 0; week < HISTORY_WEEKS; week++) {
            Instant weekStart = historyStart.plus(Duration.ofDays(7L * week));
            int txCount = 3 + random.nextInt(3); // 3-5 per week
            for (int i = 0; i < txCount; i++) {
                MerchantCategory category = categories[random.nextInt(categories.length)];
                Merchant merchant = normalMerchants.get(category);
                CategoryBaseline categoryBaseline = baseline.get(category);
                BigDecimal amount = randomAmount(categoryBaseline, random);
                Instant occurredAt = randomBusinessTimestamp(weekStart, random, historyEnd);

                ingestService.ingest(new TransactionRequest(
                        employee.getId(), merchant.getId(), amount, "USD", occurredAt, merchant.getCountry()));
            }
        }
    }

    private BigDecimal randomAmount(CategoryBaseline baseline, Random random) {
        double raw = baseline.mean() + random.nextGaussian() * baseline.stddev();
        double clamped = Math.max(baseline.mean() * 0.1, raw);
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }

    /** Business-hours-biased timestamp within the given week, never later than historyEnd. */
    private Instant randomBusinessTimestamp(Instant weekStart, Random random, Instant historyEnd) {
        int dayOffset = random.nextInt(7);
        int hour = random.nextDouble() < 0.8 ? 9 + random.nextInt(9) : random.nextInt(24);
        int minute = random.nextInt(60);
        Instant candidate = weekStart.plus(Duration.ofDays(dayOffset))
                .plus(Duration.ofHours(hour))
                .plus(Duration.ofMinutes(minute));
        return candidate.isAfter(historyEnd) ? historyEnd.minus(Duration.ofMinutes(1)) : candidate;
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
            CompanyPlan plan, Instant now) {

        Instant t0 = now.minus(Duration.ofDays(5));
        List<PlantedAnomalyOutcome> outcomes = new ArrayList<>();
        outcomes.add(plantAmountOutlier(employee, baseline, normalMerchants, plan, t0));
        outcomes.add(plantVelocity(employee, baseline, normalMerchants, plan, t0.plus(Duration.ofHours(4))));
        outcomes.add(plantDuplicate(employee, baseline, normalMerchants, plan, t0.plus(Duration.ofHours(8))));
        outcomes.add(plantGeoFirstInCountry(employee, normalMerchants, baseline, plan, t0.plus(Duration.ofHours(12))));
        // Comfortably more than 3h after the geo plant's own transaction, so policy's own 3h
        // impossible-travel lookback never sees that different-country row and picks up stray
        // geo points on top of its own signal.
        outcomes.add(plantPolicy(employee, normalMerchants, plan, t0.plus(Duration.ofHours(18))));
        outcomes.add(plantSemanticMismatch(employee, baseline, semanticMismatchMerchant, plan, t0.plus(Duration.ofHours(22))));
        return outcomes;
    }

    /**
     * Amount far outside the employee's own category baseline (z >= 4), everything else normal.
     * ruleScore alone is 30 (or more if it also happens to clear the merchant's policy limit) -
     * clears both the STANDARD (20) and PREMIUM (10) escalation thresholds on its own; the merchant
     * is familiar (used throughout normal history), so escalation here is purely threshold-driven.
     */
    private PlantedAnomalyOutcome plantAmountOutlier(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                                       Map<MerchantCategory, Merchant> normalMerchants, CompanyPlan plan, Instant at) {
        MerchantCategory category = MerchantCategory.SOFTWARE;
        Merchant merchant = normalMerchants.get(category);
        CategoryBaseline categoryBaseline = baseline.get(category);
        BigDecimal amount = BigDecimal.valueOf(categoryBaseline.mean() + 6 * categoryBaseline.stddev())
                .setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD", at, merchant.getCountry()),
                true, PlantedType.AMOUNT_OUTLIER);
        return outcomeFor(response, PlantedType.AMOUNT_OUTLIER, plan);
    }

    /**
     * Two unlabeled setup charges, then a third (tagged) completing 3 transactions within 10
     * minutes. ruleScore is 20 - clears STANDARD's threshold (20) exactly and PREMIUM's (10) with
     * room to spare; familiar merchant, so this is threshold-driven, not novelty-driven. Amounts
     * are derived from the employee's own MEALS baseline (+-5%, well under the z-score's |z|>=2
     * threshold) so this stays a clean single-signal velocity plant instead of also tripping the
     * amount z-score for an employee whose baseline happens to sit far from a fixed dollar figure.
     */
    private PlantedAnomalyOutcome plantVelocity(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                                  Map<MerchantCategory, Merchant> normalMerchants, CompanyPlan plan, Instant at) {
        Merchant merchant = normalMerchants.get(MerchantCategory.MEALS);
        CategoryBaseline mealsBaseline = baseline.get(MerchantCategory.MEALS);

        ingestService.ingest(new TransactionRequest(
                employee.getId(), merchant.getId(), scaledAmount(mealsBaseline, 0.95), "USD", at, merchant.getCountry()));
        ingestService.ingest(new TransactionRequest(
                employee.getId(), merchant.getId(), scaledAmount(mealsBaseline, 1.05), "USD",
                at.plus(Duration.ofMinutes(3)), merchant.getCountry()));

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), scaledAmount(mealsBaseline, 1.0), "USD",
                        at.plus(Duration.ofMinutes(6)), merchant.getCountry()),
                true, PlantedType.VELOCITY);
        return outcomeFor(response, PlantedType.VELOCITY, plan);
    }

    /**
     * An unlabeled charge, then a second (tagged) identical charge at the same merchant within 30
     * minutes. ruleScore is 25 - clears both the STANDARD (20) and PREMIUM (10) thresholds;
     * familiar merchant, so this is threshold-driven, not novelty-driven. Amount is the employee's
     * own TRANSPORT baseline mean (both charges must be bit-for-bit equal for the duplicate check,
     * so there's one derived amount here, not a +-5% spread like velocity's three charges).
     */
    private PlantedAnomalyOutcome plantDuplicate(Employee employee, Map<MerchantCategory, CategoryBaseline> baseline,
                                                   Map<MerchantCategory, Merchant> normalMerchants, CompanyPlan plan, Instant at) {
        Merchant merchant = normalMerchants.get(MerchantCategory.TRANSPORT);
        BigDecimal amount = scaledAmount(baseline.get(MerchantCategory.TRANSPORT), 1.0);

        ingestService.ingest(new TransactionRequest(
                employee.getId(), merchant.getId(), amount, "USD", at, merchant.getCountry()));

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD",
                        at.plus(Duration.ofMinutes(15)), merchant.getCountry()),
                true, PlantedType.DUPLICATE);
        return outcomeFor(response, PlantedType.DUPLICATE, plan);
    }

    private BigDecimal scaledAmount(CategoryBaseline baseline, double factor) {
        return BigDecimal.valueOf(baseline.mean() * factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Identical scenario on BOTH companies: a single charge at an otherwise-FAMILIAR merchant (so
     * the Fix-1 novelty path does NOT fire - this is deliberately isolated to the threshold-based
     * path only), but recorded with a transaction country the employee has never had before and no
     * nearby domestic charge (so only the soft "first time in this country" signal fires, 10 pts -
     * no impossible travel). 10 points clears PREMIUM's escalation threshold (10) but not
     * STANDARD's (20): STANDARD never even looks at this one (no escalation at all, regardless of
     * whether AI is live), while PREMIUM does - the threshold difference itself, not a different
     * scenario per company, is the tiered-depth story this plant demonstrates.
     */
    private PlantedAnomalyOutcome plantGeoFirstInCountry(Employee employee, Map<MerchantCategory, Merchant> normalMerchants,
                                             Map<MerchantCategory, CategoryBaseline> baseline, CompanyPlan plan, Instant at) {
        Merchant merchant = normalMerchants.get(MerchantCategory.TRANSPORT);
        BigDecimal amount = scaledAmount(baseline.get(MerchantCategory.TRANSPORT), 1.0);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD", at, "FR"),
                true, PlantedType.GEO);
        return outcomeFor(response, PlantedType.GEO, plan);
    }

    /**
     * Amount comfortably over the merchant's policy limit; velocity/duplicate/geo all normal.
     * ruleScore is at least 20 from the policy violation alone (usually more, since 1.5x the limit
     * is also typically a z-score outlier against the employee's own baseline) - clears both
     * thresholds; familiar merchant, so this is threshold-driven, not novelty-driven.
     */
    private PlantedAnomalyOutcome plantPolicy(Employee employee, Map<MerchantCategory, Merchant> normalMerchants,
                                                CompanyPlan plan, Instant at) {
        Merchant merchant = normalMerchants.get(MerchantCategory.OFFICE);
        BigDecimal amount = merchant.getPolicyLimit().multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), merchant.getId(), amount, "USD", at, merchant.getCountry()),
                true, PlantedType.POLICY);
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
                                                          Merchant semanticMismatchMerchant, CompanyPlan plan, Instant at) {
        CategoryBaseline officeBaseline = baseline.get(MerchantCategory.OFFICE);
        BigDecimal amount = BigDecimal.valueOf(officeBaseline.mean()).setScale(2, RoundingMode.HALF_UP);

        IngestResponse response = ingestService.ingest(
                new TransactionRequest(employee.getId(), semanticMismatchMerchant.getId(), amount, "USD",
                        at, semanticMismatchMerchant.getCountry()),
                true, PlantedType.SEMANTIC_MISMATCH);
        return outcomeFor(response, PlantedType.SEMANTIC_MISMATCH, plan);
    }

    private PlantedAnomalyOutcome outcomeFor(IngestResponse response, PlantedType type, CompanyPlan plan) {
        return new PlantedAnomalyOutcome(response.transactionId(), type, plan, response.flagged());
    }

    private record CategoryBaseline(double mean, double stddev) {
    }
}
