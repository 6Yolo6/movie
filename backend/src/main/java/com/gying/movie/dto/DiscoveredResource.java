package com.gying.movie.dto;

import lombok.Data;

@Data
public class DiscoveredResource {
    private String title;
    private String provider;
    private String url;
    private String code;
    private String source;
    private String sourceRef;
}
