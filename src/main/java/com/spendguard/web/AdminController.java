package com.spendguard.web;

import com.spendguard.seed.PlantedAnomalyOutcome;
import com.spendguard.seed.SeedDataService;
import com.spendguard.seed.SeedRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SeedDataService seedDataService;

    /** Demo-only: wipes and regenerates all data, on-demand so re-seeding during a demo is easy. */
    @PostMapping("/seed")
    public AdminSeedResponse seed() {
        SeedRunResult result = seedDataService.seed();
        return new AdminSeedResponse(
                result.companiesCreated(),
                result.employeesCreated(),
                result.merchantsCreated(),
                result.totalTransactions(),
                result.totalFlags(),
                result.plantedAnomalies().stream()
                        .map(a -> new PlantedAnomalyResult(a.transactionId(), a.plantedType(), a.companyPlan(), a.caught()))
                        .toList());
    }
}
