package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceSubmissionDTO {
    private String movieId;
    private String name;
    private String url;
    private String code;
    private String provider;
    private String type; // DISK or MAGNET or TORRENT
}
