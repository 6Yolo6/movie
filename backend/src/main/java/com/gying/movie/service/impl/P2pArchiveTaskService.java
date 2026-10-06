package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.client.P2pCloudArchiveClient;
import com.gying.movie.client.P2pCloudArchiveClient.FileData;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.entity.*;
import com.gying.movie.service.*;
import com.gying.movie.utils.ResourceHubHashUtils;
import com.gying.movie.utils.SeasonSearchUtils;
import com.gying.movie.utils.QqTransferMarker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable, isolated metadata-file queue. No discovery/restore/offline-download services are injected. */
@Service
public class P2pArchiveTaskService {
    static final String TYPE = "P2P_ARCHIVE";
    static final String LINK_SOURCE = "GYING_P2P_ARCHIVE";
    private final ResourceHubProperties properties;
    private final IResourceHubTaskService tasks;
    private final IResourceLinkService links;
    private final IMovieMetadataService movies;
    private final IQuarkTransferTaskService quarkTasks;
    private final IXunleiTransferTaskService xunleiTasks;
    private final GyingSourceClient source;
    private final P2pCloudArchiveClient cloud;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public P2pArchiveTaskService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            IResourceLinkService links, IMovieMetadataService movies, IQuarkTransferTaskService quarkTasks,
            IXunleiTransferTaskService xunleiTasks, GyingSourceClient source, P2pCloudArchiveClient cloud, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.links = links; this.movies = movies;
        this.quarkTasks = quarkTasks; this.xunleiTasks = xunleiTasks; this.source = source; this.cloud = cloud; this.mapper = mapper;
    }

    public synchronized void enqueue(String movieId, String typeCode, String mid, List<ResourceLink> selected) {
        validateSelection(movieId, selected);
        if (!Set.of("mv", "tv", "ac").contains(typeCode) || mid == null || !mid.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid source movie identity");
        }
        String fingerprint = fingerprint(selected);
        for (String provider : List.of("QUARK", "XUNLEI")) {
            if (tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("movie_id", movieId)
                    .eq("source", provider).eq("keyword", fingerprint).last("LIMIT 1"), false) != null) continue;
            ResourceHubTask task = new ResourceHubTask();
            task.setTaskType(TYPE); task.setSource(provider); task.setMovieId(movieId); task.setKeyword(fingerprint);
            task.setPriority(3); task.setMaxAttempts(3);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 1); payload.put("typeCode", typeCode); payload.put("mid", mid);
            payload.put("fingerprint", fingerprint); payload.put("resourceIds", selected.stream().map(ResourceLink::getId).toList());
            payload.put("policy", "1080P_4K_AT_LEAST_ONE_CHINESE_SUBTITLE_METADATA_FILES_ONLY");
            task.setPayload(json(payload));
            ResourceHubTask saved = tasks.enqueue(task);
            if (saved == null || saved.getId() == null) throw new IllegalStateException("P2P archive task was not persisted");
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedArchives() {
        // Single-backend deployment: only this idempotent metadata-file queue is resumed.
        // Never reset legacy discovery, cloud transfer or external publishing queues here.
        tasks.update(new UpdateWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("status", "RUNNING")
                .set("status", "FAILED").set("last_error", "Interrupted P2P upload; existing files will be verified on retry")
                .set("scheduled_at", LocalDateTime.now().plusMinutes(1)).set("updated_at", LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void runDue() {
        if (!properties.isEnabled() || !properties.getWorker().isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            List<ResourceHubTask> due = tasks.list(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE)
                    .in("status", List.of("PENDING", "FAILED")).apply("attempts < max_attempts")
                    .le("scheduled_at", LocalDateTime.now()).orderByAsc("scheduled_at").last("LIMIT 2"));
            for (ResourceHubTask task : due) runTask(task);
        } finally { running.set(false); }
    }

    void runTask(ResourceHubTask task) {
        if (!TYPE.equals(task.getTaskType())) throw new IllegalArgumentException("Not a P2P archive task");
        int attempt = (task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
        if (!tasks.update(new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("task_type", TYPE)
                .in("status", List.of("PENDING", "FAILED")).set("status", "RUNNING")
                .set("attempts", attempt).set("started_at", LocalDateTime.now()).set("updated_at", LocalDateTime.now()))) return;
        task.setStatus("RUNNING"); task.setAttempts(attempt); task.setStartedAt(LocalDateTime.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        String stage = "LOAD_SELECTION";
        try {
            payload = mapper.readValue(task.getPayload(), new TypeReference<Map<String, Object>>() {});
            List<ResourceLink> selected = new ArrayList<>();
            for (Object id : (List<?>) payload.get("resourceIds")) selected.add(links.getById(Long.valueOf(id.toString())));
            validateSelection(task.getMovieId(), selected);
            if (!Objects.equals(payload.get("fingerprint"), fingerprint(selected))) {
                task.setStatus("CANCELED"); task.setLastError("Selected P2P release was superseded"); persist(task, payload); return;
            }
            MovieMetadata movie = movies.getById(task.getMovieId());
            if (movie == null || movie.getDeletedAt() != null || "DELETED".equals(movie.getStatus())) {
                throw new IllegalStateException("Movie is not active");
            }
            String provider = task.getSource();
            if (("QUARK".equals(provider) && !properties.getQuark().isShareEnabled())
                    || ("XUNLEI".equals(provider) && !properties.getXunlei().isShareEnabled())) {
                throw new IllegalStateException("Provider sharing is disabled");
            }
            stage = "PREPARE_METADATA_FILES";
            // Retrieve and verify every torrent before making any cloud-side change.
            List<FileData> files = prepareFiles(movie, payload, selected);
            stage = "RESOLVE_MOVIE_FOLDER";
            String folder = Objects.toString(payload.get("folderId"), "");
            if (folder.isBlank()) {
                String preferred = preferredLocation(provider, movie.getId());
                folder = cloud.ensureFolder(provider, preferred, fallbackPath(provider, movie));
                payload.put("folderId", folder); persist(task, payload);
            }
            Map<String, String> uploaded = new LinkedHashMap<>();
            for (FileData file : files) {
                stage = "UPLOAD_" + (file.name().endsWith(".torrent") ? "TORRENT" : "MAGNET_TEXT");
                uploaded.put(file.name(), cloud.upload(provider, folder, file));
                payload.put("uploadedFiles", uploaded); persist(task, payload);
            }
            stage = "CREATE_METADATA_SHARE";
            String shareUrl = Objects.toString(payload.get("shareUrl"), "");
            if (shareUrl.isBlank()) {
                shareUrl = cloud.createShare(provider, folder, title(movie) + " 磁力种子（不含视频）");
                if (!("QUARK".equals(provider) ? shareUrl.startsWith("https://pan.quark.cn/s/") : shareUrl.startsWith("https://pan.xunlei.com/s/"))) {
                    throw new IllegalStateException("Provider did not return a usable own share");
                }
                payload.put("shareUrl", shareUrl); persist(task, payload);
            }
            stage = "PUBLISH_METADATA_LINK";
            ResourceLink link = links.getOne(new QueryWrapper<ResourceLink>().eq("movie_id", movie.getId())
                    .eq("source", LINK_SOURCE).eq("provider", provider).isNull("deleted_at").last("LIMIT 1"), false);
            LocalDateTime now = LocalDateTime.now();
            if (link == null) { link = new ResourceLink(); link.setMovieId(movie.getId()); link.setCreatedAt(now); }
            String quality = String.join(" / ", selected.stream().map(ResourceLink::getQuality).distinct().sorted().toList());
            link.setName(trim("《" + title(movie) + "》" + quality + " 磁力种子（含中字版本，不含视频）", 255));
            // Do not masquerade as a playable/video DISK resource or satisfy video-transfer checks.
            link.setType("TORRENT"); link.setProvider(provider); link.setSource(LINK_SOURCE);
            link.setSourceRef(trim("P2P:" + movie.getId(), 100)); link.setSourceUrl(selected.get(0).getSourceUrl());
            link.setUrl(shareUrl); link.setUrlHash(ResourceHubHashUtils.sha256(shareUrl));
            link.setQuality(quality); link.setSubtitle("含中文字幕版本（来源标注）");
            link.setVersionNote("仅磁力文本和种子文件，不含视频；字幕依据上游标注，未播放验证");
            link.setAuditStatus(1); link.setStatus("ACTIVE"); link.setLinkStatus("NORMAL"); link.setAutoCollected(true);
            link.setReportCount(0); link.setValidatedAt(now); link.setUpdatedAt(now);
            if (!(link.getId() == null ? links.save(link) : links.updateById(link))) throw new IllegalStateException("Archive share not persisted");
            payload.put("resourceLinkId", link.getId()); payload.put("fileCount", files.size());
            payload.put("totalBytes", files.stream().mapToLong(file -> file.bytes().length).sum());
            payload.put("stage", "COMPLETE"); task.setStatus("SUCCEEDED"); task.setLastError(null); task.setFinishedAt(now); persist(task, payload);
        } catch (Exception error) {
            payload.put("stage", stage); payload.put("errorCategory", error.getClass().getSimpleName());
            task.setStatus("FAILED"); task.setLastError("P2P archive failed at " + stage + " (" + error.getClass().getSimpleName() + ")");
            task.setScheduledAt(LocalDateTime.now().plusMinutes(30)); task.setFinishedAt(LocalDateTime.now()); persist(task, payload);
        }
    }

    List<FileData> prepareFiles(MovieMetadata movie, Map<String, Object> payload, List<ResourceLink> selected) {
        List<FileData> files = new ArrayList<>();
        for (ResourceLink link : selected) {
            String infoHash;
            byte[] content;
            if ("MAGNET".equals(link.getType())) {
                infoHash = magnetHash(link.getUrl());
                content = (title(movie) + "\n" + link.getQuality() + " / " + Objects.toString(link.getSubtitle(), "字幕未标注")
                        + "\n仅磁力与种子，不含视频。\n" + link.getUrl() + "\n").getBytes(StandardCharsets.UTF_8);
            } else {
                String ref = link.getSourceRef();
                if (ref == null || !ref.matches("[A-Za-z0-9]+")) throw new IllegalStateException("Stable BT identity missing");
                Map<String, Object> torrent = source.get("/torrent-file/" + payload.get("typeCode") + "/" + payload.get("mid") + "/" + ref);
                String encoded = Objects.toString(torrent.get("dataBase64"), "");
                if (encoded.length() > 2800000) throw new IllegalStateException("Torrent metadata too large");
                content = Base64.getDecoder().decode(encoded);
                infoHash = Objects.toString(torrent.get("infoHash"), "");
                String expected = selected.stream().filter(other -> "MAGNET".equals(other.getType())
                        && Objects.equals(other.getSourceRef(), ref)).map(other -> magnetHash(other.getUrl())).findFirst().orElseThrow();
                if (!expected.equals(infoHash) || !sha256(content).equals(torrent.get("sha256"))) throw new IllegalStateException("Torrent integrity mismatch");
            }
            String name = link.getQuality() + (hasChinese(link) ? "_中文字幕_" : "_") + infoHash.substring(0, 12)
                    + ("MAGNET".equals(link.getType()) ? ".txt" : ".torrent");
            files.add(new FileData(name, content));
        }
        return files;
    }
    static void validateSelection(String movieId, List<ResourceLink> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > 4) throw new IllegalArgumentException("At most two P2P releases are allowed");
        Set<String> slots = new HashSet<>();
        for (ResourceLink link : selected) {
            if (link == null || link.getId() == null || !Objects.equals(movieId, link.getMovieId()) || link.getDeletedAt() != null
                    || !"P2P".equals(link.getProvider()) || !"GYING".equals(link.getSource())
                    || !("MAGNET".equals(link.getType()) || "TORRENT".equals(link.getType()))
                    || !("1080P".equals(link.getQuality()) || "4K".equals(link.getQuality()))
                    || !"ACTIVE".equals(link.getStatus()) || !Objects.equals(1, link.getAuditStatus())
                    || !slots.add(link.getQuality() + ":" + link.getType())) throw new IllegalArgumentException("Invalid bounded P2P selection");
        }
        if (selected.stream().noneMatch(P2pArchiveTaskService::hasChinese)) throw new IllegalArgumentException("Explicit Chinese subtitles required");
    }
    static boolean hasChinese(ResourceLink link) { return "中文字幕（来源标注）".equals(link.getSubtitle()); }
    static String fingerprint(List<ResourceLink> selected) {
        return ResourceHubHashUtils.sha256(String.join("|", selected.stream().map(link -> link.getQuality() + ":" + link.getType()
                + ":" + link.getSourceRef() + ":" + ("MAGNET".equals(link.getType()) ? magnetHash(link.getUrl()) : "torrent"))
                .sorted().toList()));
    }
    private String preferredLocation(String provider, String movieId) {
        if ("QUARK".equals(provider)) {
            for (QuarkTransferTask task : quarkTasks.list(new QueryWrapper<QuarkTransferTask>().eq("movie_id", movieId)
                    .eq("status", "SUCCEEDED").isNotNull("saved_path").orderByDesc("updated_at").last("LIMIT 20"))) {
                if (!QqTransferMarker.isTemporary(task.getRequestPayload())) return task.getSavedPath();
            }
        } else {
            for (XunleiTransferTask task : xunleiTasks.list(new QueryWrapper<XunleiTransferTask>().eq("movie_id", movieId)
                    .eq("status", "SUCCEEDED").isNotNull("saved_path").orderByDesc("updated_at").last("LIMIT 20"))) {
                if (!QqTransferMarker.isTemporary(task.getRequestPayload())) return task.getSavedPath();
            }
        }
        return null;
    }
    private String fallbackPath(String provider, MovieMetadata movie) {
        if ("XUNLEI".equals(provider)) {
            XunleiTransferTask task = new XunleiTransferTask(); task.setMovieId(movie.getId());
            return XunleiTransferRunnerServiceImpl.transferPath(properties, task, title(movie));
        }
        String base = properties.getQuark().getSavePath();
        if (base == null || base.isBlank()) base = "/GYing Resource Hub";
        String category = "tv".equals(movie.getCategory()) ? "tv" : "ac".equals(movie.getCategory()) ? "anime" : "movie";
        String path = base.replaceAll("/+$", "") + "/" + category + "/" + safe(SeasonSearchUtils.baseTitle(title(movie))) + "（" + safe(movie.getId()) + "）";
        if (movie.getSeason() != null && movie.getSeason() > 0 && !"movie".equals(category)) path += "/" + safe(SeasonSearchUtils.seasonLabel(movie.getSeason()));
        return path;
    }
    private void persist(ResourceHubTask task, Map<String, Object> payload) {
        task.setPayload(json(payload)); task.setUpdatedAt(LocalDateTime.now());
        if (!tasks.updateById(task)) throw new IllegalStateException("P2P archive checkpoint was not persisted");
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("Invalid P2P audit payload"); } }
    private static String title(MovieMetadata movie) { return movie.getTitleCn() == null || movie.getTitleCn().isBlank() ? movie.getId() : movie.getTitleCn(); }
    private static String safe(String text) { return text.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"); }
    private static String trim(String text, int max) { return text.length() <= max ? text : text.substring(0, max); }
    static String magnetHash(String url) {
        var matcher = Pattern.compile("(?i)urn:btih:([0-9a-f]{40})(?:&|$)").matcher(Objects.toString(url, ""));
        if (!matcher.find()) throw new IllegalArgumentException("Valid v1 magnet required");
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw new IllegalStateException("Digest unavailable"); } }
}
