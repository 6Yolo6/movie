package com.gying.movie.dto;

import lombok.Data;

@Data
public class ResourceHubConfigResponse {
    private boolean xunleiAuthorizationConfigured;
    private String xunleiAuthorizationExpiresAt;
    private boolean xunleiAuthorizationExpired;
    private boolean xunleiCaptchaConfigured;
    private boolean enabled;
    private boolean autoApprove;
    private boolean tmdbConfigured;
    private boolean tmdbAutoSyncEnabled;
    private String tmdbAutoSyncSources;
    private int tmdbAutoSyncPage;
    private int tmdbAutoSyncMaxItems;
    private int tmdbAutoSyncIntervalHours;
    private boolean tmdbAutoDiscoveryEnabled;
    private int tmdbDiscoveryMaxResults;
    private int tmdbDiscoveryCooldownHours;
    private boolean gyingDiscoveryEnabled;
    private boolean gyingAutoSyncEnabled;
    private String gyingAutoSyncSources;
    private int gyingAutoSyncPage;
    private int gyingAutoSyncMaxItems;
    private int gyingAutoSyncIntervalHours;
    private boolean workerEnabled;
    private long workerFixedDelayMs;
    private int workerTaskLimit;
    private int workerQuarkLimit;
    private int workerPublishLimit;
    private boolean discoveredRetryEnabled;
    private int discoveredRetryLimit;
    private long discoveredRetryDelayMs;
    private String discoveredRetryCron;
}
