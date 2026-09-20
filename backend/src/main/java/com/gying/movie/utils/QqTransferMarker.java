package com.gying.movie.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QqTransferMarker {
    public static final String ORIGIN = "QQ_BOT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QqTransferMarker() {
    }

    public static String payload(String targetPath) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("origin", ORIGIN);
            value.put("targetPath", targetPath);
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create QQ temporary transfer marker", error);
        }
    }

    public static boolean isTemporary(String payload) {
        return ORIGIN.equalsIgnoreCase(text(payload, "origin"));
    }

    public static String targetPath(String payload) {
        return text(payload, "targetPath");
    }

    private static String text(String payload, String field) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            String value = root.path(field).asText(null);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
