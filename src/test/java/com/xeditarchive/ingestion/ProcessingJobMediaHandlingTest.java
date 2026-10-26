package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ProcessingJobMediaHandlingTest {

    @Autowired
    private ProcessingJobService processingJobService;

    @Autowired
    private IngestionRepository ingestionRepository;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Test
    void processJob_marksMediaUnavailable_whenNoMediaIsAvailable() {
        IngestionResponse response = ingestionService.createIngestion("https://x.com/example/status/123");

        ProcessingJobResponse jobResponse = processingJobService.processForIngestion(response.id());

        assertThat(jobResponse.status()).isEqualTo(ProcessingJobStatus.MEDIA_UNAVAILABLE.name());

        ProcessingJob job = processingJobRepository.findTopByIngestionIdOrderByCreatedAtDesc(response.id()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ProcessingJobStatus.MEDIA_UNAVAILABLE);
        assertThat(ingestionRepository.findById(response.id()).orElseThrow().getStatus())
                .isEqualTo(IngestionStatus.MEDIA_UNAVAILABLE);
    }
}
