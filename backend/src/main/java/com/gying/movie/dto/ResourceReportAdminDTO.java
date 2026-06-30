package com.gying.movie.dto;

import com.gying.movie.entity.ResourceReport;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceReportAdminDTO extends ResourceReport {
    private String movieId;
    private String movieTitle;
    private String resourceName;
    private String provider;
    private String url;
    private String linkStatus;
    private String uploaderName;
    private String reporterName;
}