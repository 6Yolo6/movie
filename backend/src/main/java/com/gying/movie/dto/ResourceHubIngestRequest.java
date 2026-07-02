package com.gying.movie.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class ResourceHubIngestRequest {
    private Long taskId;
    private String movieId;
    private String source;
    private String sourceRef;
    private String sourceUrl;
    private String name;
    private String type;
    private String provider;
    private String url;
    private String code;
    private String quality;
    private String subtitle;
    private String fileSize;
    private String versionNote;
    private BigDecimal confidence;
    private Boolean autoApprove;
}
