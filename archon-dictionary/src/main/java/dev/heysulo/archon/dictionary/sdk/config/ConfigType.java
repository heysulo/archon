package dev.heysulo.archon.dictionary.sdk.config;

import java.math.BigDecimal;
import java.util.Locale;

/** The basic types a configuration value can have. Values travel and are stored as canonical strings. */
public enum ConfigType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN;

    public static final int MAX_VALUE_LENGTH = 16_384;

    /**
     * The canonical form of a value of this type, such as {@code "42"} for {@code " 042 "} as an INTEGER.
     *
     * @throws IllegalArgumentException if the value is not of this type
     */
    public String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A value is required");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Values are at most " + MAX_VALUE_LENGTH + " characters");
        }
        String trimmed = value.trim();
        try {
            return switch (this) {
                case STRING -> value;
                case INTEGER -> Long.toString(Long.parseLong(trimmed));
                case DECIMAL -> new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
                case BOOLEAN -> {
                    String lower = trimmed.toLowerCase(Locale.ROOT);
                    if (!lower.equals("true") && !lower.equals("false")) {
                        throw new IllegalArgumentException("not true or false");
                    }
                    yield lower;
                }
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not " + article() + " " + label());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + value + "' is not " + article() + " " + label());
        }
    }

    private String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    private String article() {
        return this == INTEGER ? "an" : "a";
    }
}
