package com.gying.movie.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import lombok.Data;

/** Nullable workflow fields support partial edits; capability metadata is response-only. */
@Data
public class CloudDriveWorkflowConfig {
    private String provider;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private boolean supported;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    @JsonDeserialize(using = StrictBoolean.class)
    private Boolean discoveryEnabled;
    @JsonDeserialize(using = StrictBoolean.class)
    private Boolean transferEnabled;
    @JsonDeserialize(using = StrictBoolean.class)
    private Boolean gyingPublishEnabled;
    @JsonDeserialize(using = StrictBoolean.class)
    private Boolean shareEnabled;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean credentialConfigured;
    private String savePath;
    @JsonDeserialize(using = StrictInteger.class)
    private Integer batchLimit;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        // Jackson can route ignored read-only fields to the any-setter.
        if (java.util.Set.of("supported", "status", "credentialConfigured").contains(field)) return;
        throw new IllegalArgumentException("Unknown cloud drive workflow field");
    }

    public static class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
                return context.reportInputMismatch(Boolean.class, "Expected a JSON boolean");
            }
            return parser.getBooleanValue();
        }
    }

    public static class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Integer.class, "Expected a JSON integer");
            }
            return parser.getIntValue();
        }
    }
}
