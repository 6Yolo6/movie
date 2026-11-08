package com.gying.movie.config;

import lombok.Data;
import com.gying.movie.dto.MetadataCrawlRange;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "resource-hub")
public class ResourceHubProperties {
    private boolean enabled = false;
    private boolean autoApprove = true;
    private Tmdb tmdb = new Tmdb();
    private Gying gying = new Gying();
    private Pansou pansou = new Pansou();
    private Quark quark = new Quark();
    private Xunlei xunlei = new Xunlei();
    private Baidu baidu = new Baidu();
    private Worker worker = new Worker();
    private QqChannelPublisher qqChannelPublisher = new QqChannelPublisher();

    public boolean isDriveDiscoveryEnabled(String provider) {
        CloudDriveProvider drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isDiscoveryEnabled();
            case XUNLEI -> xunlei.isDiscoveryEnabled();
            case BAIDU -> baidu.isDiscoveryEnabled();
        };
    }

    public boolean isDriveTransferEnabled(String provider) {
        CloudDriveProvider drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isTransferEnabled();
            case XUNLEI -> xunlei.isEnabled();
            case BAIDU -> baidu.isEnabled();
        };
    }

    public boolean isDriveGyingPublishEnabled(String provider) {
        CloudDriveProvider drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isGyingPublishEnabled();
            case XUNLEI -> xunlei.isGyingPublishEnabled();
            case BAIDU -> baidu.isGyingPublishEnabled();
        };
    }

    public boolean isDriveShareEnabled(String provider) {
        CloudDriveProvider drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isShareEnabled();
            case XUNLEI -> xunlei.isShareEnabled();
            case BAIDU -> baidu.isShareEnabled();
        };
    }

    /** File policy is independent of workflow stages; unknown providers never collect. */
    public boolean isDriveVideoEnabled(String provider) {
        var drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isVideoEnabled();
            case XUNLEI -> xunlei.isVideoEnabled();
            case BAIDU -> baidu.isVideoEnabled();
        };
    }

    public boolean isDriveTorrentEnabled(String provider) {
        var drive = CloudDriveProvider.from(provider);
        if (drive == null) return false;
        return switch (drive) {
            case QUARK -> quark.isTorrentEnabled();
            case XUNLEI -> xunlei.isTorrentEnabled();
            case BAIDU -> baidu.isTorrentEnabled();
        };
    }

    public boolean isDriveFileCollectionEnabled(String provider) {
        return isDriveVideoEnabled(provider) || isDriveTorrentEnabled(provider);
    }

    /** Legacy Quark/Xunlei transfers restore video trees; torrents use the small-file archive. */
    public boolean isDriveShareCollectionEnabled(String provider) {
        return isDriveDiscoveryEnabled(provider) && ("BAIDU".equalsIgnoreCase(provider)
                ? isDriveFileCollectionEnabled(provider) : isDriveVideoEnabled(provider));
    }

    public boolean isDriveShareTransferEnabled(String provider) {
        return isDriveTransferEnabled(provider) && ("BAIDU".equalsIgnoreCase(provider)
                ? isDriveFileCollectionEnabled(provider) : isDriveVideoEnabled(provider));
    }

    @Data
    public static class Tmdb {
        private String baseUrl = "https://api.themoviedb.org/3";
        private String apiKey;
        private boolean autoSyncEnabled = false;
        private String autoSyncSources = "TRENDING_MOVIE_DAY,TRENDING_TV_DAY,POPULAR_MOVIE,POPULAR_TV";
        private int autoSyncPage = 1;
        private int autoSyncEndPage;
        private Map<String, MetadataCrawlRange> autoSyncRanges = Map.of();

        public MetadataCrawlRange rangeFor(String source) {
            return autoSyncRanges.getOrDefault(source, new MetadataCrawlRange(autoSyncPage, getAutoSyncEndPage()));
        }

        // Missing range configuration preserves the legacy single-page scope.
        public int getAutoSyncEndPage() {
            return Math.max(autoSyncPage, autoSyncEndPage);
        }
        private int autoSyncMaxItems = 20;
        private int autoSyncIntervalHours = 24;
        private boolean autoDiscoveryEnabled = true;
        private int discoveryMaxResults = 10;
        private int discoveryCooldownHours = 24;
        private long requestIntervalMs = 250;
        private int maxRetries = 2;
    }

    @Data
    public static class Gying {
        private boolean discoveryEnabled = true;
        private boolean autoSyncEnabled = false;
        private String autoSyncSources = "HITS_MOVIE,HITS_TV,HITS_ANIME,CSCORE_MOVIE,CSCORE_TV,CSCORE_ANIME";
        private volatile Map<String, Boolean> autoSyncPausedSources = Map.of();

        public void setAutoSyncPausedSources(Map<String, Boolean> paused) {
            autoSyncPausedSources = Map.copyOf(paused);
        }

        public boolean isAutoSyncSourcePaused(String source) {
            return Boolean.TRUE.equals(autoSyncPausedSources.get(source));
        }

        private int autoSyncPage = 1;
        private int autoSyncEndPage;
        private Map<String, MetadataCrawlRange> autoSyncRanges = Map.of();

        public MetadataCrawlRange rangeFor(String source) {
            return autoSyncRanges.getOrDefault(source, new MetadataCrawlRange(autoSyncPage, getAutoSyncEndPage()));
        }

        // Missing range configuration preserves the legacy single-page scope.
        public int getAutoSyncEndPage() {
            return Math.max(autoSyncPage, autoSyncEndPage);
        }
        private int autoSyncMaxItems = 10;
        private int autoSyncIntervalHours = 24;
    }

    @Data
    public static class Pansou {
        private String baseUrl = "http://localhost:8888";
        private String token;
        private String apiBaseUrl = "https://www.panso.best";
        private String apiKey;
    }

    @Data
    public static class Quark {
        private boolean videoEnabled = true;
        private boolean torrentEnabled = true;
        private boolean discoveryEnabled = true;
        private boolean transferEnabled = true;
        private boolean gyingPublishEnabled = true;
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
        private int sharePollAttempts = 30;
        private long sharePollIntervalMs = 1000;
        private DirectoryAi directoryAi = new DirectoryAi();
    }

    @Data
    public static class DirectoryAi {
        private boolean enabled = true;
        private String baseUrl = "http://host.docker.internal:21434";
        private String model = "qwen3:4b-instruct-2507-q4_K_M";
        private int timeoutSeconds = 20;
    }

    @Data
    public static class Xunlei {
        private boolean videoEnabled = true;
        private boolean torrentEnabled = true;
        private boolean discoveryEnabled = true;
        private boolean gyingPublishEnabled = true;
        private boolean enabled = false;
        private String baseUrl = "https://api-pan.xunlei.com/drive/v1";
        private String authorization;
        private String account;
        private String password;
        private String refreshToken;
        private String clientId;
        private String clientSecret;
        private String deviceId;
        private String clientVersion = "1.82.0";
        private String captchaToken;
        private String tokenStatePath = "/app/data/xunlei-auth.json";
        private String savePath = "/影视剧资源分享(先转存后再查看)/GYing Resource Hub";
        private boolean shareEnabled = false;
        private String shareCreatePath = "/share";
        private int pollAttempts = 20;
        private long pollIntervalMs = 1000;
    }

    @Data
    public static class Baidu {
        private boolean videoEnabled = false;
        private boolean torrentEnabled = false;
        // New provider defaults require explicit page configuration and account login.
        private boolean discoveryEnabled = false;
        private boolean enabled = false;
        private boolean gyingPublishEnabled = false;
        private boolean shareEnabled = false;
        private String sessionStatePath = "/app/data/baidu/session.enc";
        private String sessionKeyPath = "/app/data/baidu/session.key";
        private String loginRequestPath = "/app/data/baidu/login-request.json";
        private String savePath = "/GYing Resource Hub";
        private int pollAttempts = 20;
        private long pollIntervalMs = 1000;
    }

    @Data
    public static class Worker {
        private boolean enabled = false;
        private long fixedDelayMs = 60000;
        private int taskLimit = 5;
        private int quarkLimit = 5;
        private int xunleiLimit = 5;
        private int baiduLimit = 5;
        private int publishLimit = 20;
        private boolean discoveredRetryEnabled = true;
        private int discoveredRetryLimit = 20;
        private long discoveredRetryDelayMs = 5000;
        private String discoveredRetryCron = "0 30 8 * * *";
    }

    @Data
    public static class QqChannelPublisher {
        private String baseUrl = "http://localhost:8092";
        private String token;
    }
}
