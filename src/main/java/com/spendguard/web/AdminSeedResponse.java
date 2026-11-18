package com.spendguard.web;

import java.util.List;

public record AdminSeedResponse(
        int companiesCreated,
        int employeesCreated,
        int merchantsCreated,
        int totalTransactions,
        int totalFlags,
        List<PlantedAnomalyResult> plantedAnomalies
) {
}
