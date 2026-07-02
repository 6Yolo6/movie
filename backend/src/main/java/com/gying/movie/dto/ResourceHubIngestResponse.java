package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceHubIngestResponse {
    private Long resourceId;
    private Long discoveryResultId;
    private boolean duplicate;
    private String status;
}
