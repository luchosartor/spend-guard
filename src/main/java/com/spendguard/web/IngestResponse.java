package com.spendguard.web;

public record IngestResponse(Long transactionId, boolean flagged, Long flagId) {
}
