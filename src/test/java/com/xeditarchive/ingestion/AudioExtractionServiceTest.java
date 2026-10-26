package com.xeditarchive.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioExtractionServiceTest {

    private final AudioExtractionService service = new AudioExtractionService(MediaProperties.defaults());

    @Test
    void extractSample_reportsUnavailable_whenSourceIsMissing(@TempDir Path dir) {
        AudioExtractionResult result = service.extractSample(dir.resolve("missing.mp4"), dir.resolve("out.wav"));

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("ffmpeg");
    }

    @Test
    void extractSample_reportsFailure_forNonMediaFile(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        Path bogus = Files.writeString(dir.resolve("bogus.mp4"), "not a video");

        AudioExtractionResult result = service.extractSample(bogus, dir.resolve("out.wav"));

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).startsWith("ffmpeg extraction failed");
        assertThat(dir.resolve("out.wav")).doesNotExist();
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".log"));
        }
    }

    @Test
    void extractSample_producesMono16kWav_fromGeneratedVideo(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        Path video = TestMedia.generateVideoWithTone(dir.resolve("edit.mp4"));

        AudioExtractionResult result = service.extractSample(video, dir.resolve("out.wav"));

        assertThat(result.success()).as(result.reason()).isTrue();
        byte[] header = Files.readAllBytes(result.outputPath());
        assertThat(new String(header, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(header, 8, 4)).isEqualTo("WAVE");
        int channels = (header[22] & 0xff) | (header[23] & 0xff) << 8;
        int sampleRate = (header[24] & 0xff) | (header[25] & 0xff) << 8 | (header[26] & 0xff) << 16;
        assertThat(channels).isEqualTo(1);
        assertThat(sampleRate).isEqualTo(16000);
    }

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
