package com.xeditarchive.ingestion;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A media file that has been copied into a private, per-job temporary workspace.
 * Closing it deletes the whole workspace (media, extracted audio, FFmpeg logs).
 */
public final class FetchedMedia implements AutoCloseable {

    private final Path file;
    private final Path workspace;
    private final String source;

    public FetchedMedia(Path file, Path workspace, String source) {
        this.file = Objects.requireNonNull(file);
        this.workspace = Objects.requireNonNull(workspace);
        this.source = source;
    }

    /** Local media file that FFmpeg can read. */
    public Path file() {
        return file;
    }

    /** Directory that holds all temporary artifacts for this job. */
    public Path workspace() {
        return workspace;
    }

    /** Human-readable provenance, e.g. "x-api" or "local-sample". */
    public String source() {
        return source;
    }

    @Override
    public void close() {
        MediaWorkspaces.deleteRecursively(workspace);
    }
}
