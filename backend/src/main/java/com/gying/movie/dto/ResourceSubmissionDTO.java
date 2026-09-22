package com.gying.movie.dto;

import java.util.List;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class ResourceSubmissionDTO {
    @NotBlank @Size(max = 64)
    private String movieId;
    @Size(max = 255)
    private String name;
    @NotBlank @Size(max = 2048)
    private String url;
    @Size(max = 32)
    private String code;
    @Size(max = 50)
    private String provider;
    @Size(max = 20)
    private String type; // DISK or MAGNET or TORRENT
    @Size(max = 50)
    private String quality;
    @Size(max = 50)
    private String subtitle;
    @Size(max = 50)
    private String fileSize;
    @Size(max = 255)
    private String versionNote;
    @Size(max = 50)
    private List<@NotBlank @Size(max = 64) String> bindMovieIds;
}
