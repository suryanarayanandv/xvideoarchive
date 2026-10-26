package com.xeditarchive.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Development/validation provider that serves media you are permitted to use (your own uploads,
 * licensed samples) from a local directory. The file is chosen by post ID: {@code <dir>/<postId>.mp4}
 * (also .mov, .m4a, .mp3, .wav). The original is copied into the job workspace, so cleanup never
 * touches the sample library. Enabled with {@code media.provider=local-sample}.
 */
@Component
@ConditionalOnProperty(name = "media.provider", havingValue = "local-sample")
public class LocalSampleMediaClient implements XPostMediaClient {

    private static final List<String> EXTENSIONS = List.of(".mp4", ".mov", ".m4a", ".mp3", ".wav");

    private final MediaProperties properties;
    private final Path sampleDirectory;

    public LocalSampleMediaClient(MediaProperties properties,
                                  @Value("${media.local-sample.directory:}") String sampleDirectory) {
        this.properties = properties;
        this.sampleDirectory = sampleDirectory == null || sampleDirectory.isBlank()
                ? null
                : Path.of(sampleDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void verifyConfigured() {
        if (sampleDirectory == null || !Files.isDirectory(sampleDirectory)) {
            throw new MediaProviderNotConfiguredException(
                    "media.provider=local-sample is selected but MEDIA_SAMPLE_DIR is not a directory.");
        }
    }

    @Override
    public PostMediaFetchResult fetchMedia(Ingestion ingestion) {
        try {
            verifyConfigured();
        } catch (MediaProviderNotConfiguredException e) {
            return PostMediaFetchResult.notConfigured(e.getMessage());
        }
        String postId = ingestion.getPlatformPostId();
        if (postId == null || !postId.matches("\\d{1,25}")) {
            return PostMediaFetchResult.unavailable("Post ID is not numeric.");
        }

        Optional<Path> source = EXTENSIONS.stream()
                .map(extension -> sampleDirectory.resolve(postId + extension).normalize())
                .filter(path -> path.startsWith(sampleDirectory))
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .findFirst();
        if (source.isEmpty()) {
            return PostMediaFetchResult.unavailable("No permitted local sample exists for post " + postId + ".");
        }

        Path workspace = null;
        try {
            long size = Files.size(source.get());
            if (size == 0 || size > properties.maxDownloadBytes()) {
                return PostMediaFetchResult.unavailable("Sample is " + size + " bytes; allowed range is 1.."
                        + properties.maxDownloadBytes() + ".");
            }
            workspace = MediaWorkspaces.create(properties, ingestion.getId());
            String fileName = source.get().getFileName().toString();
            Path target = workspace.resolve("media" + fileName.substring(fileName.lastIndexOf('.')));
            Files.copy(source.get(), target);
            return PostMediaFetchResult.available(new FetchedMedia(target, workspace, "local-sample"));
        } catch (IOException e) {
            MediaWorkspaces.deleteRecursively(workspace);
            return PostMediaFetchResult.transientFailure("Could not stage local sample: " + e.getMessage());
        }
    }
}
