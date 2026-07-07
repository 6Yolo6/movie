package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceHubConfigRequest {
    private Boolean enabled;
    private Boolean autoApprove;
    private Boolean tmdbAutoSyncEnabled;
    private String tmdbAutoSyncSources;
    private Integer tmdbAutoSyncPage;
    private Integer tmdbAutoSyncMaxItems;
    private Integer tmdbAutoSyncIntervalHours;
    private Boolean tmdbAutoDiscoveryEnabled;
    private Integer tmdbDiscoveryMaxResults;
    private Integer tmdbDiscoveryCooldownHours;
    private Boolean workerEnabled;
    private Integer workerTaskLimit;
    private Integer workerQuarkLimit;
    private Integer workerPublishLimit;
}
