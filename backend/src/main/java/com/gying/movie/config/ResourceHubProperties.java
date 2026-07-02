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
