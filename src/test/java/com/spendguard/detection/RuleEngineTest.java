package com.spendguard.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.spendguard.domain.GeoAnomalyType;
import com.spendguard.domain.MerchantCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private final RuleEngine ruleEngine = new RuleEngine();
    private final Instant now = Instant.parse("2026-11-18T12:00:00Z").truncatedTo(ChronoUnit.SECONDS);

    // ---- amount z-score -------------------------------------------------

    @Test
    void amountZScore_triggersOnFarOutlierWithEnoughHistory() {
        List<HistoryItem> history = fiveHistoryItemsAtMealsAmount(new BigDecimal("50.00"));
        // mean ~50, stddev ~0 historically tight; a $500 charge is a huge outlier.
        RuleEngineInput input = input(new BigDecimal("500.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isNotNull();
        assertThat(Math.abs(signals.amountZScore())).isGreaterThanOrEqualTo(4.0);
        assertThat(signals.ruleScore()).isEqualTo(30);
    }

    @Test
    void amountZScore_doesNotTriggerWithinTwoStdDevsDespiteEnoughHistory() {
        // Mean ~50 with real variance (not the zero-stddev special case), new amount is within 2 stddev.
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("40.00"), MerchantCategory.MEALS, "US", now.minus(1, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("50.00"), MerchantCategory.MEALS, "US", now.minus(2, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("45.00"), MerchantCategory.MEALS, "US", now.minus(3, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("55.00"), MerchantCategory.MEALS, "US", now.minus(4, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("50.00"), MerchantCategory.MEALS, "US", now.minus(5, ChronoUnit.DAYS), 1L));
        RuleEngineInput input = input(new BigDecimal("52.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isNotNull();
        assertThat(Math.abs(signals.amountZScore())).isLessThan(2.0);
        assertThat(signals.ruleScore()).isZero();
    }

    @Test
    void amountZScore_exactlyFiveHistoryEntriesIsEnoughToCompute() {
        // Boundary: "at least 5" means 5 is already enough, not just ">5".
        RuleEngineInput input = input(new BigDecimal("101.00"), MerchantCategory.MEALS, "US", null, cleanMealsHistory());

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isNotNull();
    }

    @Test
    void amountZScore_atExactlyTwoStdDevsScoresZeroPoints() {
        // mean=100, stddev=10 (see cleanMealsHistory): amount at exactly 2 stddev -> |z|=2.0.
        RuleEngineInput input = input(new BigDecimal("120.00"), MerchantCategory.MEALS, "US", null, cleanMealsHistory());

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isEqualTo(2.0);
        assertThat(signals.ruleScore()).isZero();
    }

    @Test
    void amountZScore_atExactlyThreeStdDevsScoresFifteenPoints() {
        RuleEngineInput input = input(new BigDecimal("130.00"), MerchantCategory.MEALS, "US", null, cleanMealsHistory());

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isEqualTo(3.0);
        assertThat(signals.ruleScore()).isEqualTo(15);
    }

    @Test
    void amountZScore_atExactlyFourStdDevsScoresThirtyPoints() {
        RuleEngineInput input = input(new BigDecimal("140.00"), MerchantCategory.MEALS, "US", null, cleanMealsHistory());

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isEqualTo(4.0);
        assertThat(signals.ruleScore()).isEqualTo(30);
    }

    /** mean=100, sample stddev=10 exactly: amounts 90,90,100,110,110 (sum of squared deviations=400, /4=100). */
    private List<HistoryItem> cleanMealsHistory() {
        return List.of(
                new HistoryItem(new BigDecimal("90.00"), MerchantCategory.MEALS, "US", now.minus(1, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("90.00"), MerchantCategory.MEALS, "US", now.minus(2, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("100.00"), MerchantCategory.MEALS, "US", now.minus(3, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("110.00"), MerchantCategory.MEALS, "US", now.minus(4, ChronoUnit.DAYS), 1L),
                new HistoryItem(new BigDecimal("110.00"), MerchantCategory.MEALS, "US", now.minus(5, ChronoUnit.DAYS), 1L));
    }

    @Test
    void amountZScore_skippedWithFewerThanFiveHistoryEntries() {
        List<HistoryItem> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            history.add(new HistoryItem(new BigDecimal("50.00"), MerchantCategory.MEALS, "US",
                    now.minus(i + 1, ChronoUnit.DAYS), 1L));
        }
        RuleEngineInput input = input(new BigDecimal("500.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.amountZScore()).isNull();
        assertThat(signals.ruleScore()).isZero();
    }

    // ---- velocity ---------------------------------------------------------

    @Test
    void velocity_triggersOnThreeTransactionsWithinTenMinutes() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("10.00"), MerchantCategory.MEALS, "US", now.minus(2, ChronoUnit.MINUTES), 1L),
                new HistoryItem(new BigDecimal("12.00"), MerchantCategory.MEALS, "US", now.minus(5, ChronoUnit.MINUTES), 1L));
        RuleEngineInput input = input(new BigDecimal("15.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.velocityTriggered()).isTrue();
        assertThat(signals.ruleScore()).isEqualTo(20);
    }

    @Test
    void velocity_doesNotTriggerWithOnlyTwoTransactionsInWindow() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("10.00"), MerchantCategory.MEALS, "US", now.minus(2, ChronoUnit.MINUTES), 1L));
        RuleEngineInput input = input(new BigDecimal("15.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.velocityTriggered()).isFalse();
        assertThat(signals.ruleScore()).isZero();
    }

    // ---- duplicate ----------------------------------------------------------

    @Test
    void duplicate_triggersOnSameMerchantSameAmountWithinThirtyMinutes() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", now.minus(15, ChronoUnit.MINUTES), 7L));
        RuleEngineInput input = input(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", 7L, history, null);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.duplicateTriggered()).isTrue();
        assertThat(signals.ruleScore()).isEqualTo(25);
    }

    @Test
    void duplicate_triggersOnIdenticalTimestamp() {
        // A processor retry: the exact same payload POSTed twice with an identical timestamp. The
        // history-filter boundary is "!isAfter", not strict "isBefore", specifically so this counts
        // as prior history instead of being silently excluded (self-exclusion is handled by id
        // upstream, not by this time comparison).
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", now, 7L));
        RuleEngineInput input = input(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", 7L, history, null);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.duplicateTriggered()).isTrue();
        assertThat(signals.ruleScore()).isEqualTo(25);
    }

    @Test
    void duplicate_doesNotTriggerWhenAmountDiffers() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", now.minus(15, ChronoUnit.MINUTES), 7L));
        RuleEngineInput input = input(new BigDecimal("43.50"), MerchantCategory.TRANSPORT, "US", 7L, history, null);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.duplicateTriggered()).isFalse();
        assertThat(signals.ruleScore()).isZero();
    }

    // ---- geo ----------------------------------------------------------------

    @Test
    void geo_impossibleTravelTriggersOnDifferentCountryWithinThreeHours() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("20.00"), MerchantCategory.TRANSPORT, "US", now.minus(1, ChronoUnit.HOURS), 1L));
        RuleEngineInput input = input(new BigDecimal("30.00"), MerchantCategory.MEALS, "IT", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.geoAnomalyType()).isEqualTo(GeoAnomalyType.IMPOSSIBLE_TRAVEL);
        assertThat(signals.ruleScore()).isEqualTo(25);
    }

    @Test
    void geo_firstInCountryTriggersWhenNoPriorTransactionThere() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("20.00"), MerchantCategory.TRANSPORT, "US", now.minus(10, ChronoUnit.DAYS), 1L));
        RuleEngineInput input = input(new BigDecimal("30.00"), MerchantCategory.MEALS, "FR", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.geoAnomalyType()).isEqualTo(GeoAnomalyType.FIRST_IN_COUNTRY);
        assertThat(signals.ruleScore()).isEqualTo(10);
    }

    @Test
    void geo_noneWhenCountryAlreadySeenAndNoImpossibleTravel() {
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("20.00"), MerchantCategory.TRANSPORT, "US", now.minus(10, ChronoUnit.DAYS), 1L));
        RuleEngineInput input = input(new BigDecimal("30.00"), MerchantCategory.MEALS, "US", null, history);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.geoAnomalyType()).isEqualTo(GeoAnomalyType.NONE);
        assertThat(signals.ruleScore()).isZero();
    }

    // ---- policy ---------------------------------------------------------------

    @Test
    void policy_violationTriggersWhenAmountExceedsLimit() {
        // One unrelated same-country history entry so this isn't also the employee's first-ever
        // transaction in "US" (which would add the first-in-country geo points too).
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("20.00"), MerchantCategory.TRANSPORT, "US", now.minus(10, ChronoUnit.DAYS), 1L));
        RuleEngineInput input = input(new BigDecimal("150.00"), MerchantCategory.MEALS, "US", null, history, new BigDecimal("100.00"));

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.policyViolation()).isTrue();
        assertThat(signals.ruleScore()).isEqualTo(20);
    }

    @Test
    void policy_noViolationWhenUnderLimitOrLimitIsNull() {
        RuleEngineInput underLimit = input(new BigDecimal("80.00"), MerchantCategory.MEALS, "US", null, List.of(), new BigDecimal("100.00"));
        RuleEngineInput noLimit = input(new BigDecimal("999999.00"), MerchantCategory.MEALS, "US", null, List.of(), null);

        assertThat(ruleEngine.evaluate(underLimit).policyViolation()).isFalse();
        assertThat(ruleEngine.evaluate(noLimit).policyViolation()).isFalse();
    }

    // ---- future-dated history is never leaked ----------------------------------

    @Test
    void historyAfterTheCurrentTransactionIsIgnored() {
        // A "duplicate" that happens chronologically AFTER the current transaction must not count.
        List<HistoryItem> history = List.of(
                new HistoryItem(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", now.plus(15, ChronoUnit.MINUTES), 7L));
        RuleEngineInput input = input(new BigDecimal("42.00"), MerchantCategory.TRANSPORT, "US", 7L, history, null);

        RuleSignals signals = ruleEngine.evaluate(input);

        assertThat(signals.duplicateTriggered()).isFalse();
    }

    // ---- helpers ---------------------------------------------------------------

    private List<HistoryItem> fiveHistoryItemsAtMealsAmount(BigDecimal amount) {
        List<HistoryItem> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(new HistoryItem(amount, MerchantCategory.MEALS, "US", now.minus(i + 1, ChronoUnit.DAYS), 1L));
        }
        return history;
    }

    private RuleEngineInput input(BigDecimal amount, MerchantCategory category, String country,
                                    Long merchantId, List<HistoryItem> history) {
        return input(amount, category, country, merchantId, history, null);
    }

    private RuleEngineInput input(BigDecimal amount, MerchantCategory category, String country,
                                    Long merchantId, List<HistoryItem> history, BigDecimal policyLimit) {
        return new RuleEngineInput(amount, category, country, now, merchantId, policyLimit, history);
    }
}
