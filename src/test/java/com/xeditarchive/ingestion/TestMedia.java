package com.xeditarchive.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Generates synthetic, rights-free media with FFmpeg for tests. */
final class TestMedia {

    private TestMedia() {
    }

    /** 3-second 320x240 black H.264 video with a 440 Hz AAC tone. */
    static Path generateVideoWithTone(Path target) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=320x240:r=15:d=3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-shortest", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                target.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.exists(target)) {
            process.destroyForcibly();
            throw new IllegalStateException("Could not generate test video with ffmpeg");
        }
        return target;
    }
}
