package com.xeditarchive.ingestion;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingestions")
    public ResponseEntity<IngestionResponse> createIngestion(@Valid @RequestBody IngestionRequest request) {
        return ResponseEntity.ok(ingestionService.createIngestion(request.url()));
    }

    @GetMapping("/ingestions/{id}")
    public ResponseEntity<IngestionResponse> getIngestion(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ingestionService.findById(id));
    }
}
