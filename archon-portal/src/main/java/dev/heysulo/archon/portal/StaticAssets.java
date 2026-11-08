package dev.heysulo.archon.portal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built frontend, read from the classpath. Files are small and fixed for the life of the process, so each is
 * read once and kept in memory.
 */
final class StaticAssets {
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("map", "application/json"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));
    private static final String INDEX = "/index.html";

    /** A file of the frontend. */
    record Asset(byte[] body, String contentType) {
    }

    private final String root;
    private final Map<String, Optional<Asset>> cache = new ConcurrentHashMap<>();

    /**
     * @param root the classpath directory holding the build, such as {@code /portal}
     */
    StaticAssets(String root) {
        this.root = root;
    }

    /** The app's page, or empty if the frontend was not built into this jar. */
    Optional<Asset> index() {
        return find(INDEX);
    }

    /**
     * @param path the request path, such as {@code /assets/index-4-Q8NGPI.js}
     * @return the file, or empty if there is none or the path tries to leave the build directory
     */
    Optional<Asset> find(String path) {
        if (!isSafe(path)) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(path, this::read);
    }

    /** Whether the path names a file, as opposed to an app route like {@code /processes}. */
    static boolean looksLikeFile(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.contains(".");
    }

    private static boolean isSafe(String path) {
        return path.startsWith("/") && !path.contains("..") && !path.contains("\\") && !path.contains("//")
                && !path.endsWith("/");
    }

    private Optional<Asset> read(String path) {
        try (InputStream in = StaticAssets.class.getResourceAsStream(root + path)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new Asset(in.readAllBytes(), contentType(path)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String contentType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
