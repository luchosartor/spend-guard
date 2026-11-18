package com.spendguard.seed;

import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.PlantedType;

public record PlantedAnomalyOutcome(Long transactionId, PlantedType plantedType, CompanyPlan companyPlan, boolean caught) {
}
