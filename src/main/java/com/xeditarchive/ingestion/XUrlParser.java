package com.xeditarchive.ingestion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

public final class XUrlParser {

    private XUrlParser() {
    }

    public static ParsedXPost parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidXPostUrlException("URL is required");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new InvalidXPostUrlException("URL is not valid: " + rawUrl);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidXPostUrlException("Only http and https URLs are supported");
        }

        if (host == null || !("x.com".equalsIgnoreCase(host) || "www.x.com".equalsIgnoreCase(host)
                || "twitter.com".equalsIgnoreCase(host) || "www.twitter.com".equalsIgnoreCase(host))) {
            throw new InvalidXPostUrlException("Only X post URLs are supported");
        }

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new InvalidXPostUrlException("URL must include a post path");
        }

        String[] segments = path.split("/");
        if (segments.length < 4) {
            throw new InvalidXPostUrlException("URL must match the pattern https://x.com/<user>/status/<id>");
        }

        String postId = segments[segments.length - 1];
        if (!postId.chars().allMatch(Character::isDigit)) {
            throw new InvalidXPostUrlException("URL must end with a numeric post ID");
        }

        String statusToken = Arrays.stream(segments)
                .filter(segment -> "status".equalsIgnoreCase(segment))
                .findFirst()
                .orElse(null);
        if (statusToken == null) {
            throw new InvalidXPostUrlException("URL must include /status/<id>");
        }

        return new ParsedXPost("x", postId, rawUrl.trim());
    }

    public record ParsedXPost(String platform, String platformPostId, String normalizedUrl) {
    }
}
