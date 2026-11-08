package com.gying.movie.config;

import java.util.Locale;

/** Provider catalogue. Planned providers cannot execute cloud workflows. */
public enum CloudDriveProvider {
    QUARK(true), XUNLEI(true), BAIDU(false);

    private final boolean supported;

    CloudDriveProvider(boolean supported) {
        this.supported = supported;
    }

    public boolean isSupported() {
        return supported;
    }

    public String status() {
        return supported ? "AVAILABLE" : "PLANNED";
    }

    public static CloudDriveProvider from(String provider) {
        if (provider == null || provider.isBlank()) return null;
        try {
            return valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
