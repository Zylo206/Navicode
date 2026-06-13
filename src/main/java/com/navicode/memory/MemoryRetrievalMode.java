package com.navicode.memory;

import java.util.Locale;

public enum MemoryRetrievalMode {
    LONG_TERM_ONLY("long_term_only", false),
    LONG_PLUS_SHORT("long_plus_short", true);

    private static final String PROPERTY = "navicode.memory.retrieval";
    private static final String ENV = "NAVICODE_MEMORY_RETRIEVAL";

    private final String configValue;
    private final boolean experimental;

    MemoryRetrievalMode(String configValue, boolean experimental) {
        this.configValue = configValue;
        this.experimental = experimental;
    }

    public String configValue() {
        return configValue;
    }

    public boolean isExperimental() {
        return experimental;
    }

    public boolean includesShortTerm() {
        return this == LONG_PLUS_SHORT;
    }

    public static MemoryRetrievalMode configured() {
        return parse(firstNonBlank(System.getProperty(PROPERTY), System.getenv(ENV)));
    }

    public static MemoryRetrievalMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LONG_TERM_ONLY;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (MemoryRetrievalMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        return LONG_TERM_ONLY;
    }

    public String displayName() {
        return experimental ? configValue + " (experimental)" : configValue;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
