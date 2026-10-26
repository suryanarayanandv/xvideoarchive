package com.xeditarchive.ingestion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProcessingJobController {

    private final ProcessingJobService processingJobService;

    public ProcessingJobController(ProcessingJobService processingJobService) {
        this.processingJobService = processingJobService;
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<ProcessingJobResponse> getJob(@PathVariable Long id) {
        return ResponseEntity.ok(processingJobService.getById(id));
    }

    @GetMapping("/jobs/by-ingestion/{ingestionId}")
    public ResponseEntity<ProcessingJobResponse> getJobByIngestion(@PathVariable Long ingestionId) {
        return ResponseEntity.ok(processingJobService.getByIngestionId(ingestionId));
    }

    @PostMapping("/jobs/{ingestionId}/process")
    public ResponseEntity<ProcessingJobResponse> processJob(@PathVariable Long ingestionId) {
        return ResponseEntity.ok(processingJobService.processForIngestion(ingestionId));
    }
}
