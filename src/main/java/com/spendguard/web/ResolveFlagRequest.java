package com.spendguard.web;

import com.spendguard.domain.FlagStatus;
import jakarta.validation.constraints.NotNull;

public record ResolveFlagRequest(@NotNull FlagStatus decision, String note) {
}
