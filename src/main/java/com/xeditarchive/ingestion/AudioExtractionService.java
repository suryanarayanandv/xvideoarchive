package com.xeditarchive.ingestion;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
public class AudioExtractionService {

    private static final int MAX_ERROR_CHARS = 500;

    private final MediaProperties properties;

    public AudioExtractionService(MediaProperties properties) {
        this.properties = properties;
    }

    public AudioExtractionResult extractSample(Path sourceFile, Path targetFile) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return AudioExtractionResult.unavailable("ffmpeg extraction skipped: source media file does not exist");
        }
        if (!isFfmpegAvailable()) {
            return AudioExtractionResult.unavailable("ffmpeg is not installed or is not available on PATH");
        }

        Process process = null;
        Path log = null;
        try {
            Path parent = targetFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write FFmpeg output to a file so a chatty process can never block on a full pipe.
            log = Files.createTempFile(parent, "ffmpeg-", ".log");

            ProcessBuilder processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", sourceFile.toAbsolutePath().toString(),
                    "-t", "30",
                    "-vn",
                    "-acodec", "pcm_s16le",
                    "-ar", "16000",
                    "-ac", "1",
                    targetFile.toAbsolutePath().toString()
            );
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(log.toFile());

            process = processBuilder.start();
            boolean finished = process.waitFor(properties.ffmpegTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(targetFile);
                return AudioExtractionResult.unavailable("ffmpeg extraction timed out after "
                        + properties.ffmpegTimeout().toSeconds() + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 || !Files.exists(targetFile) || Files.size(targetFile) == 0) {
                Files.deleteIfExists(targetFile);
                return AudioExtractionResult.unavailable("ffmpeg extraction failed: " + summarize(log));
            }
            return AudioExtractionResult.success(targetFile);
        } catch (IOException e) {
            return AudioExtractionResult.unavailable("ffmpeg extraction failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AudioExtractionResult.unavailable("ffmpeg extraction interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // the job workspace is removed afterwards
                }
            }
        }
    }

    private static String summarize(Path log) {
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8).trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                return "no output";
            }
            return text.length() > MAX_ERROR_CHARS ? text.substring(text.length() - MAX_ERROR_CHARS) : text;
        } catch (IOException e) {
            return "no output";
        }
    }

    private boolean isFfmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
