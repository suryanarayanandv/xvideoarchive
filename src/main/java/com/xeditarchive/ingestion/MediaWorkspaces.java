package com.xeditarchive.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/** Creates and removes private per-job temporary directories. */
final class MediaWorkspaces {

    private static final Logger log = LoggerFactory.getLogger(MediaWorkspaces.class);

    private MediaWorkspaces() {
    }

    static Path baseDirectory(MediaProperties properties) {
        String configured = properties.tempDir();
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "x-edit-archive")
                : Path.of(configured);
    }

    static Path create(MediaProperties properties, Long ingestionId) throws IOException {
        Path base = baseDirectory(properties);
        Files.createDirectories(base);
        String prefix = "job-" + ingestionId + "-";
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(base, prefix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory(base, prefix);
    }

    static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete temporary media path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean temporary media workspace {}: {}", directory, e.getMessage());
        }
    }
}
