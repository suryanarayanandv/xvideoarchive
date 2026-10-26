package com.xeditarchive.ingestion;

public record ProcessingJobResponse(Long id, Long ingestionId, String jobType, String status) {
    public static ProcessingJobResponse from(ProcessingJob job) {
        return new ProcessingJobResponse(job.getId(), job.getIngestionId(), job.getJobType(), job.getStatus().name());
    }
}
