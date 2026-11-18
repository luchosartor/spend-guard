package com.spendguard.seed;

import java.util.List;

public record SeedRunResult(
        int companiesCreated,
        int employeesCreated,
        int merchantsCreated,
        int totalTransactions,
        int totalFlags,
        List<PlantedAnomalyOutcome> plantedAnomalies
) {
}
