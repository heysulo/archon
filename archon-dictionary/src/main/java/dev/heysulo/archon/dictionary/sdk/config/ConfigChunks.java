package dev.heysulo.archon.dictionary.sdk.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Splits configurations into pieces that each fit comfortably in one message. DataBridge refuses messages over
 * 16 MB, and 100,000 configurations sent at once would be well over that.
 */
public final class ConfigChunks {
    /** A quarter of DataBridge's limit, leaving room for serialization overhead. */
    public static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
    // Field names, class descriptors and the numbers around the text
    private static final int ENTRY_OVERHEAD_BYTES = 256;

    private ConfigChunks() {
    }

    /** @return at least one piece, empty if there is nothing to send, so the receiver always hears the last one */
    public static <T> List<List<T>> split(List<T> items, ToIntFunction<T> size, int maxBytes) {
        List<List<T>> chunks = new ArrayList<>();
        List<T> current = new ArrayList<>();
        long bytes = 0;
        for (T item : items) {
            int itemBytes = size.applyAsInt(item);
            if (!current.isEmpty() && bytes + itemBytes > maxBytes) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(item);
            bytes += itemBytes;
        }
        chunks.add(current);
        return chunks;
    }

    /** A generous estimate of an entry's serialized size: two bytes per character, plus overhead. */
    public static int size(ConfigEntry entry) {
        return ENTRY_OVERHEAD_BYTES + 2 * (length(entry.group()) + length(entry.application()) + length(entry.name())
                + length(entry.value()) + length(entry.description()) + length(entry.updatedAt())
                + length(entry.updatedBy()));
    }

    public static int size(ConfigRow row) {
        return size(row.entry());
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
