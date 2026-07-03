package com.gying.movie.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qq-bot")
public class QqBotProperties {
    private boolean enabled = false;
    private String webhookToken;
    private String allowedGroups;
    private String commandPrefixes = "找,搜,/movie,/search";
    private int maxResults = 3;
    private boolean autoTransfer = true;
    private Napcat napcat = new Napcat();

    @Data
    public static class Napcat {
        private String baseUrl = "http://localhost:3000";
        private String accessToken;
    }
}
