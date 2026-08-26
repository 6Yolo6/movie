package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceHubConfigRequest {
    /** Runtime-only credentials. Blank values keep the current process value. */
    private String xunleiAuthorization;
    private String xunleiCaptchaToken;
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
    private Boolean gyingDiscoveryEnabled;
    private Boolean gyingAutoSyncEnabled;
    private String gyingAutoSyncSources;
    private Integer gyingAutoSyncPage;
    private Integer gyingAutoSyncMaxItems;
    private Integer gyingAutoSyncIntervalHours;
    private Boolean workerEnabled;
    private Integer workerTaskLimit;
    private Integer workerQuarkLimit;
    private Integer workerPublishLimit;
}
