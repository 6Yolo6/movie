package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceHubMetadataSyncRequest {
    private String source;
    private Integer page;
    private Integer maxItems;
    private Boolean runNow;
}
