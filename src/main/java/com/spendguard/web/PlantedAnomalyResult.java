package com.spendguard.web;

import com.spendguard.domain.CompanyPlan;
import com.spendguard.domain.PlantedType;

/** Demo-only: proves detection recall. Never used outside the seed endpoint's own response. */
public record PlantedAnomalyResult(Long transactionId, PlantedType plantedType, CompanyPlan companyPlan, boolean caught) {
}
