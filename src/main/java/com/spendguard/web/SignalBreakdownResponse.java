package com.spendguard.web;

import com.spendguard.domain.GeoAnomalyType;

public record SignalBreakdownResponse(
        Double amountZScore,
        boolean velocityTriggered,
        boolean duplicateTriggered,
        GeoAnomalyType geoAnomalyType,
        boolean policyViolation
) {
}
