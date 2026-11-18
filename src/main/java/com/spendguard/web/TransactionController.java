package com.spendguard.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionIngestService ingestService;

    @PostMapping
    public IngestResponse ingest(@Valid @RequestBody TransactionRequest request) {
        return ingestService.ingest(request);
    }
}
