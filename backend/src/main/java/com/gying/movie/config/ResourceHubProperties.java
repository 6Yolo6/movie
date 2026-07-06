package com.gying.movie.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "resource-hub")
public class ResourceHubProperties {
    private boolean enabled = false;
    private boolean autoApprove = true;
    private Tmdb tmdb = new Tmdb();
    private Pansou pansou = new Pansou();
    private Quark quark = new Quark();
    private Worker worker = new Worker();

    @Data
    public static class Tmdb {
        private String apiKey;
        private boolean autoSyncEnabled = false;
        private String autoSyncSources = "TRENDING_MOVIE_DAY,TRENDING_TV_DAY,POPULAR_MOVIE,POPULAR_TV";
        private int autoSyncPage = 1;
        private int autoSyncMaxItems = 20;
        private int autoSyncIntervalHours = 24;
        private boolean autoDiscoveryEnabled = true;
        private int discoveryMaxResults = 10;
        private int discoveryCooldownHours = 24;
    }

    @Data
    public static class Pansou {
        private String baseUrl = "http://localhost:8888";
        private String token;
    }

    @Data
    public static class Quark {
        private String baseUrl = "http://localhost:5005";
        private String token;
        private String savePath = "/GYing Resource Hub";
        private String pattern = "(.*)\\.(mp4|mkv|avi|mov|flv|wmv|webm|m4v|ts)";
        private String replace = "";
        private boolean runImmediately = true;
        private boolean shareEnabled = true;
        private int shareUrlType = 1;
        private int shareExpiredType = 1;
        private String sharePasscode = "";
        private int sharePollAttempts = 12;
        private long sharePollIntervalMs = 500;
    }

    @Data
    public static class Worker {
        private boolean enabled = false;
        private long fixedDelayMs = 60000;
        private int taskLimit = 5;
        private int quarkLimit = 5;
        private int publishLimit = 20;
    }
}
