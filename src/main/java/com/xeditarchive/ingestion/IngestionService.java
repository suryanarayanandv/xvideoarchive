package com.xeditarchive.ingestion;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class IngestionService {

    private final IngestionRepository ingestionRepository;
    private final ProcessingJobService processingJobService;

    public IngestionService(IngestionRepository ingestionRepository, ProcessingJobService processingJobService) {
        this.ingestionRepository = ingestionRepository;
        this.processingJobService = processingJobService;
    }

    @Transactional
    public IngestionResponse createIngestion(String rawUrl) {
        XUrlParser.ParsedXPost parsed = XUrlParser.parse(rawUrl);

        Optional<Ingestion> existingByPlatform = ingestionRepository.findByPlatformAndPlatformPostId(
                parsed.platform(), parsed.platformPostId());
        if (existingByPlatform.isPresent()) {
            processingJobService.createForIngestion(existingByPlatform.get().getId());
            return IngestionResponse.from(existingByPlatform.get());
        }

        Optional<Ingestion> existingByUrl = ingestionRepository.findByPostUrl(parsed.normalizedUrl());
        if (existingByUrl.isPresent()) {
            processingJobService.createForIngestion(existingByUrl.get().getId());
            return IngestionResponse.from(existingByUrl.get());
        }

        Ingestion ingestion = new Ingestion(parsed.platform(), parsed.platformPostId(), parsed.normalizedUrl());
        Ingestion saved = ingestionRepository.save(ingestion);
        processingJobService.createForIngestion(saved.getId());
        return IngestionResponse.from(saved);
    }

    public IngestionResponse findById(Long id) {
        return ingestionRepository.findById(id)
                .map(IngestionResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + id));
    }
}
