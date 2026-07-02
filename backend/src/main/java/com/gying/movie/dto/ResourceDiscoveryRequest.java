package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceDiscoveryRequest {
    private String movieId;
    private String keyword;
    private String source;
    private Integer maxResults;
    private Boolean refresh;
    private Boolean runNow;
}
