package com.gying.movie.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qq-bot")
public class QqBotProperties {
    private boolean enabled = false;
    private String webhookToken;
    private String allowedGroups;
    private String commandPrefixes = "\u641c\u7d22,\u641c,\u627e,/movie,/search";
    private int maxResults = 3;
    private int minKeywordLength = 2;
    private int rateLimitPerMinute = 5;
    private String blockedKeywords;
    private boolean autoTransfer = true;
    private String defaultReply = "机器人使用方法：@机器人 搜/找 影片名\n"
            + "影片上下文保留 5 分钟；先选择影片，再从资源名称/画质候选中回复单个序号。"
            + "可回复“夸克”或“迅雷”筛选；不支持按数量批量转存。";
    private String replyProvider = "napcat";
    private Napcat napcat = new Napcat();
    private Qqbot qqbot = new Qqbot();

    @Data
    public static class Napcat {
        private String baseUrl = "http://localhost:3000";
        private String accessToken;
    }

    @Data
    public static class Qqbot {
        private String appId;
        private String clientSecret;
        private String groupOpenids;
        private String apiBaseUrl = "https://api.sgroup.qq.com";
        private String tokenUrl = "https://bots.qq.com/app/getAppAccessToken";
    }
}
