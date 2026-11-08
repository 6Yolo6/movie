package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.client.GyingSourceClient;
import com.gying.movie.client.BaiduWebClient;
import com.gying.movie.client.BaiduActionException;
import com.gying.movie.client.P2pCloudArchiveClient;
import com.gying.movie.client.P2pCloudArchiveClient.FileData;
import com.gying.movie.config.ResourceHubProperties;
import com.gying.movie.dto.QuarkTransferRunResult;
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
    private final IBaiduTransferTaskService baiduTasks;
    private final GyingSourceClient source;
    private final P2pCloudArchiveClient cloud;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    public P2pArchiveTaskService(ResourceHubProperties properties, IResourceHubTaskService tasks,
            IResourceLinkService links, IMovieMetadataService movies, IQuarkTransferTaskService quarkTasks,
            IXunleiTransferTaskService xunleiTasks, IBaiduTransferTaskService baiduTasks, GyingSourceClient source, P2pCloudArchiveClient cloud, ObjectMapper mapper) {
        this.properties = properties; this.tasks = tasks; this.links = links; this.movies = movies;
        this.quarkTasks = quarkTasks; this.xunleiTasks = xunleiTasks; this.baiduTasks = baiduTasks; this.source = source; this.cloud = cloud; this.mapper = mapper;
    }

    public synchronized void enqueue(String movieId, String typeCode, String mid, List<ResourceLink> selected) {
        validateSelection(movieId, selected);
        if (!Set.of("mv", "tv", "ac").contains(typeCode) || mid == null || !mid.matches("[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("Invalid source movie identity");
        }
        String fingerprint = fingerprint(selected);
        for (String provider : List.of("QUARK", "XUNLEI", "BAIDU")) {
            if (!properties.isDriveDiscoveryEnabled(provider) || !properties.isDriveTorrentEnabled(provider)) continue;
            if (tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE).eq("movie_id", movieId)
                    .eq("source", provider).eq("keyword", fingerprint).last("LIMIT 1"), false) != null) continue;
            ResourceHubTask task = new ResourceHubTask();
            task.setTaskType(TYPE); task.setSource(provider); task.setMovieId(movieId); task.setKeyword(fingerprint);
            task.setPriority(3); task.setMaxAttempts(3);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", 2); payload.put("typeCode", typeCode); payload.put("mid", mid);
            payload.put("fingerprint", fingerprint);
            payload.put("resourceIds", selected.stream().map(ResourceLink::getId).filter(Objects::nonNull).toList());
            // Persist only the paired magnet ID. The authenticated URL never enters the database.
            payload.put("torrentMagnetIds", selected.stream().filter(link -> link.getId() == null)
                    .map(torrent -> pairedMagnet(torrent, selected).getId()).toList());
            payload.put("policy", "1080P_4K_AT_LEAST_ONE_CHINESE_SUBTITLE_METADATA_FILES_ONLY");
            task.setPayload(json(payload));
            ResourceHubTask saved = tasks.enqueue(task);
            if (saved == null || saved.getId() == null) throw new IllegalStateException("P2P archive task was not persisted");
        }
    }

    /** Reuse local immutable selection only; remote checkpoints belong to the original provider/account. */
    public synchronized ResourceHubTask cloneExistingForProvider(Long existingId, String provider, Long parentId) {
        requireProvider(provider);
        if (!canRunArchive(provider)) throw new IllegalStateException("P2P_ARCHIVE_DISABLED");
        ResourceHubTask original = tasks.getById(existingId);
        if (original == null || !TYPE.equals(original.getTaskType())) throw new IllegalArgumentException("Invalid P2P archive reference");
        Map<String, Object> old;
        try { old = mapper.readValue(original.getPayload(), new TypeReference<Map<String, Object>>() {}); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid P2P archive selection"); }
        List<ResourceLink> selected = loadSelection(old);
        validateSelection(original.getMovieId(), selected);
        String identity = fingerprint(selected);
        if (!identity.equals(old.get("fingerprint"))) throw new IllegalArgumentException("P2P selection superseded");
        ResourceHubTask existing = tasks.getOne(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE)
                .eq("movie_id", original.getMovieId()).eq("source", provider).eq("keyword", identity)
                .orderByAsc("id").last("LIMIT 1"), false);
        if (existing != null) return existing;
        String typeCode = Objects.toString(old.get("typeCode"), "");
        String mid = Objects.toString(old.get("mid"), "");
        if (!Set.of("mv", "tv", "ac").contains(typeCode) || !mid.matches("[A-Za-z0-9]+")) throw new IllegalArgumentException("Invalid source movie identity");
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String field : List.of("version", "typeCode", "mid", "fingerprint", "resourceIds", "torrentMagnetIds", "policy")) {
            if (old.containsKey(field)) clean.put(field, old.get(field));
        }
        clean.putIfAbsent("version", 1);
        clean.put("backfillParentId", parentId);
        ResourceHubTask child = new ResourceHubTask();
        child.setTaskType(TYPE); child.setSource(provider); child.setMovieId(original.getMovieId());
        child.setKeyword(identity); child.setPriority(3); child.setMaxAttempts(3); child.setPayload(json(clean));
        ResourceHubTask saved = tasks.enqueue(child);
        if (saved == null || saved.getId() == null) throw new IllegalStateException("P2P archive task was not persisted");
        return saved;
    }

    /** Exact-child entry point; never drains another movie or provider queue. */
    public ResourceHubTask submitOne(Long id, String provider) {
        requireProvider(provider);
        ResourceHubTask task = tasks.getById(id);
        if (task == null || !TYPE.equals(task.getTaskType()) || !provider.equals(task.getSource())) throw new IllegalArgumentException("Invalid P2P archive task");
        if (!canRunArchive(provider) || !running.compareAndSet(false, true)) return task;
        try {
            if ("SUCCEEDED".equals(task.getStatus())) {
                if ("BAIDU".equals(provider)) {
                    Map<String, Object> payload;
                    try { payload = mapper.readValue(task.getPayload(), new TypeReference<Map<String, Object>>() {}); }
                    catch (Exception invalid) { throw new IllegalStateException("Invalid P2P archive checkpoint"); }
                    String folder = Objects.toString(payload.get("folderId"), "");
                    if (folder.isBlank()) throw new IllegalStateException("Missing P2P folder");
                    cloud.verifyMetadataOnlyFolder(provider, folder);
                    String verified = cloud.findOwnShare(provider, folder);
                    if (!usableOwnShare(provider, verified)) throw new IllegalStateException("P2P own share unavailable");
                    Object resourceId = payload.get("resourceLinkId");
                    ResourceLink resource = resourceId == null ? null : links.getById(Long.valueOf(resourceId.toString()));
                    if (resource == null || !provider.equals(resource.getProvider()) || !"TORRENT".equals(resource.getType())
                            || !LINK_SOURCE.equals(resource.getSource()) || !Objects.equals(task.getMovieId(), resource.getMovieId())
                            || resource.getDeletedAt() != null || !"ACTIVE".equals(resource.getStatus())) throw new IllegalStateException("P2P resource checkpoint invalid");
                    if (!links.update(new UpdateWrapper<ResourceLink>().eq("id", resource.getId()).eq("provider", provider)
                            .eq("source", LINK_SOURCE).eq("type", "TORRENT").isNull("deleted_at")
                            .set("url", verified).set("url_hash", ResourceHubHashUtils.sha256(verified)).set("link_status", "NORMAL")
                            .set("validated_at", LocalDateTime.now()).set("updated_at", LocalDateTime.now()))) throw new IllegalStateException("P2P own share not persisted");
                    if (!verified.equals(payload.get("shareUrl"))) { payload.put("shareUrl", verified); persist(task, payload); }
                }
                return task;
            }
            if (!Set.of("PENDING", "FAILED").contains(task.getStatus())
                    || (task.getAttempts() != null && task.getMaxAttempts() != null && task.getAttempts() >= task.getMaxAttempts())) return task;
            runTask(task);
            return task;
        } finally { running.set(false); }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedArchives() {
        // Single-backend deployment: only this idempotent metadata-file queue is resumed.
        // Never reset legacy discovery, cloud transfer or external publishing queues here.
        List<String> providers = List.of("QUARK", "XUNLEI", "BAIDU").stream().filter(this::canRunArchive).toList();
        if (providers.isEmpty()) return;
        tasks.update(new UpdateWrapper<ResourceHubTask>().eq("task_type", TYPE).in("source", providers).eq("status", "RUNNING")
                .set("status", "FAILED").set("last_error", "Interrupted P2P upload; existing files will be verified on retry")
                .set("scheduled_at", LocalDateTime.now().plusMinutes(1)).set("updated_at", LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void runDue() {
        if (!properties.isEnabled() || !properties.getWorker().isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            List<String> providers = List.of("QUARK", "XUNLEI", "BAIDU").stream().filter(this::canRunArchive).toList();
            if (providers.isEmpty()) return;
            List<ResourceHubTask> due = tasks.list(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE)
                    .in("source", providers).in("status", List.of("PENDING", "FAILED")).apply("attempts < max_attempts")
                    .apply("JSON_EXTRACT(payload, '$.backfillParentId') IS NULL")
                    .le("scheduled_at", LocalDateTime.now()).orderByAsc("scheduled_at").last("LIMIT 2"));
            for (ResourceHubTask task : due) runTask(task);
        } finally { running.set(false); }
    }

    /** Explicit single-provider execution; worker scheduling is not required. */
    public QuarkTransferRunResult submitPending(String provider, int limit) {
        requireProvider(provider);
        QuarkTransferRunResult result = new QuarkTransferRunResult();
        if (!canRunArchive(provider) || !running.compareAndSet(false, true)) { result.setSkipped(1); return result; }
        try {
            List<ResourceHubTask> pending = tasks.list(new QueryWrapper<ResourceHubTask>().eq("task_type", TYPE)
                    .eq("source", provider).in("status", List.of("PENDING", "FAILED")).apply("attempts < max_attempts")
                    .apply("JSON_EXTRACT(payload, '$.backfillParentId') IS NULL")
                    .le("scheduled_at", LocalDateTime.now()).orderByAsc("scheduled_at").last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
            if (pending == null) return result;
            for (ResourceHubTask task : pending) {
                if (task == null || task.getId() == null || !TYPE.equals(task.getTaskType()) || !provider.equals(task.getSource())) {
                    result.setSkipped(result.getSkipped() + 1); continue;
                }
                int before = task.getAttempts() == null ? 0 : task.getAttempts();
                runTask(task);
                if (task.getAttempts() == null || task.getAttempts() == before || "CANCELED".equals(task.getStatus())) {
                    result.setSkipped(result.getSkipped() + 1);
                } else if ("SUCCEEDED".equals(task.getStatus())) {
                    result.setSubmitted(result.getSubmitted() + 1);
                } else if ("FAILED".equals(task.getStatus())) {
                    result.setFailed(result.getFailed() + 1);
                    if (result.getErrors().size() < 10) result.getErrors().add(task.getLastError());
                } else { result.setSkipped(result.getSkipped() + 1); }
            }
            return result;
        } finally { running.set(false); }
    }

    void runTask(ResourceHubTask task) {
        if (!TYPE.equals(task.getTaskType())) throw new IllegalArgumentException("Not a P2P archive task");
        String provider = requireProvider(task.getSource());
        // Disabled queues remain pending/failed with their checkpoints and retry budget intact.
        if (!canRunArchive(provider)) return;
        int attempt = (task.getAttempts() == null ? 0 : task.getAttempts()) + 1;
        if (!tasks.update(new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).eq("task_type", TYPE).eq("source", provider)
                .in("status", List.of("PENDING", "FAILED")).set("status", "RUNNING")
                .set("attempts", attempt).set("started_at", LocalDateTime.now()).set("updated_at", LocalDateTime.now()))) return;
        task.setStatus("RUNNING"); task.setAttempts(attempt); task.setStartedAt(LocalDateTime.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        String stage = "LOAD_SELECTION";
        try {
            payload = mapper.readValue(task.getPayload(), new TypeReference<Map<String, Object>>() {});
            List<ResourceLink> selected = loadSelection(payload);
            if (!Objects.equals(payload.get("fingerprint"), fingerprint(selected))) {
                task.setStatus("CANCELED"); task.setLastError("Selected P2P release was superseded"); persist(task, payload); return;
            }
            validateSelection(task.getMovieId(), selected);
            MovieMetadata movie = movies.getById(task.getMovieId());
            if (movie == null || movie.getDeletedAt() != null || "DELETED".equals(movie.getStatus())) {
                throw new IllegalStateException("Movie is not active");
            }
            if (!properties.isDriveShareEnabled(provider)) {
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
            if ("BAIDU".equals(provider)) cloud.verifyMetadataOnlyFolder(provider, folder);
            String shareUrl = Objects.toString(payload.get("shareUrl"), "");
            if (shareUrl.isBlank()) {
                if ("BAIDU".equals(provider)) {
                    if (Boolean.TRUE.equals(payload.get("baiduShareSubmitting"))) {
                        // A timeout may follow a successful create: reconcile, never submit twice.
                        shareUrl = cloud.findOwnShare(provider, folder);
                        if (shareUrl == null || shareUrl.isBlank()) throw new IllegalStateException("Baidu metadata share awaiting reconciliation");
                    } else {
                        payload.put("baiduShareSubmitting", true); persist(task, payload);
                        try { shareUrl = cloud.createShare(provider, folder, title(movie) + " 磁力种子（不含视频）"); }
                        catch (BaiduActionException rejected) {
                            if (!rejected.uncertain()) { payload.remove("baiduShareSubmitting"); persist(task, payload); }
                            throw rejected;
                        }
                    }
                } else {
                    shareUrl = cloud.createShare(provider, folder, title(movie) + " 磁力种子（不含视频）");
                }
                if (!usableOwnShare(provider, shareUrl)) throw new IllegalStateException("Provider did not return a usable own share");
                payload.put("shareUrl", shareUrl); payload.remove("baiduShareSubmitting"); persist(task, payload);
            }
            if (!usableOwnShare(provider, shareUrl)) throw new IllegalStateException("Provider did not return a usable own share");
            if ("BAIDU".equals(provider)) {
                // Stored URLs can expire: only a live own share of this exact folder may be published.
                String verified = cloud.findOwnShare(provider, folder);
                if (!usableOwnShare(provider, verified)) throw new IllegalStateException("Baidu metadata share is unavailable");
                shareUrl = verified; payload.put("shareUrl", verified); persist(task, payload);
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
            payload.remove("errorCategory");
            payload.put("stage", "COMPLETE"); task.setStatus("SUCCEEDED"); task.setLastError(null); task.setFinishedAt(now); persist(task, payload);
        } catch (Exception error) {
            payload.put("stage", stage); payload.put("errorCategory", error.getClass().getSimpleName());
            if ("BAIDU".equals(provider) && payload.get("backfillParentId") != null && error instanceof BaiduActionException action) {
                if (action.actionRequired()) task.setAttempts(Math.max(attempt - 1, 0));
                task.setStatus(action.uncertain() ? "UNKNOWN" : action.actionRequired() ? "ACTION_REQUIRED" : "FAILED");
            } else { task.setStatus("FAILED"); }
            task.setLastError("P2P archive failed at " + stage + " (" + error.getClass().getSimpleName() + ")");
            task.setScheduledAt(LocalDateTime.now().plusMinutes(30)); task.setFinishedAt(LocalDateTime.now()); persist(task, payload);
        }
    }

    List<ResourceLink> loadSelection(Map<String, Object> payload) {
        Object version = payload.getOrDefault("version", 1);
        if (!(Integer.valueOf(1).equals(version) || Integer.valueOf(2).equals(version))) {
            throw new IllegalArgumentException("Unsupported P2P archive version");
        }
        if (!(payload.get("resourceIds") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 4) {
            throw new IllegalArgumentException("Invalid P2P resource references");
        }
        List<ResourceLink> selected = new ArrayList<>();
        for (Object id : ids) selected.add(links.getById(Long.valueOf(id.toString())));
        if (Integer.valueOf(2).equals(version)) {
            if (!(payload.get("torrentMagnetIds") instanceof List<?> torrentIds) || torrentIds.size() > 2) {
                throw new IllegalArgumentException("Invalid torrent magnet references");
            }
            for (Object id : torrentIds) {
                long magnetId = Long.parseLong(id.toString());
                ResourceLink magnet = selected.stream().filter(link -> link != null && "MAGNET".equals(link.getType())
                        && Objects.equals(magnetId, link.getId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Torrent magnet reference missing"));
                selected.add(torrentDescriptor(magnet));
            }
        }
        return selected;
    }

    static ResourceLink torrentDescriptor(ResourceLink magnet) {
        ResourceLink torrent = new ResourceLink();
        torrent.setMovieId(magnet.getMovieId()); torrent.setType("TORRENT");
        torrent.setProvider(magnet.getProvider()); torrent.setSource(magnet.getSource());
        torrent.setSourceRef(magnet.getSourceRef()); torrent.setQuality(magnet.getQuality());
        torrent.setSubtitle(magnet.getSubtitle()); torrent.setStatus(magnet.getStatus());
        torrent.setAuditStatus(magnet.getAuditStatus()); torrent.setDeletedAt(magnet.getDeletedAt());
        return torrent;
    }

    private static ResourceLink pairedMagnet(ResourceLink torrent, List<ResourceLink> selected) {
        return selected.stream().filter(link -> link != null && "MAGNET".equals(link.getType())
                && Objects.equals(link.getSourceRef(), torrent.getSourceRef())
                && Objects.equals(link.getQuality(), torrent.getQuality())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Torrent requires a matching magnet"));
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
            if (link == null || (link.getId() == null && !("TORRENT".equals(link.getType()) && link.getUrl() == null))
                    || !Objects.equals(movieId, link.getMovieId()) || link.getDeletedAt() != null
                    || !"P2P".equals(link.getProvider()) || !"GYING".equals(link.getSource())
                    || !("MAGNET".equals(link.getType()) || "TORRENT".equals(link.getType()))
                    || !("1080P".equals(link.getQuality()) || "4K".equals(link.getQuality()))
                    || !"ACTIVE".equals(link.getStatus()) || !Objects.equals(1, link.getAuditStatus())
                    || !slots.add(link.getQuality() + ":" + link.getType())) throw new IllegalArgumentException("Invalid bounded P2P selection");
            if ("TORRENT".equals(link.getType())) {
                if (link.getSourceRef() == null || !link.getSourceRef().matches("[A-Za-z0-9]+")) {
                    throw new IllegalArgumentException("Stable BT identity required");
                }
                pairedMagnet(link, selected);
            }
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
        return switch (provider) {
            case "QUARK" -> quarkTasks.list(new QueryWrapper<QuarkTransferTask>().eq("movie_id", movieId)
                    .eq("status", "SUCCEEDED").isNotNull("saved_path").orderByDesc("updated_at").last("LIMIT 20")).stream()
                    .filter(task -> !QqTransferMarker.isTemporary(task.getRequestPayload()))
                    .map(QuarkTransferTask::getSavedPath).findFirst().orElse(null);
            case "XUNLEI" -> xunleiTasks.list(new QueryWrapper<XunleiTransferTask>().eq("movie_id", movieId)
                    .eq("status", "SUCCEEDED").isNotNull("saved_path").orderByDesc("updated_at").last("LIMIT 20")).stream()
                    .filter(task -> !QqTransferMarker.isTemporary(task.getRequestPayload()))
                    .map(XunleiTransferTask::getSavedPath).findFirst().orElse(null);
            case "BAIDU" -> baiduTasks.list(new QueryWrapper<BaiduTransferTask>().eq("movie_id", movieId)
                    .eq("status", "SUCCEEDED").isNotNull("saved_path").orderByDesc("updated_at").last("LIMIT 20")).stream()
                    .filter(task -> !QqTransferMarker.isTemporary(task.getRequestPayload()))
                    .map(BaiduTransferTask::getSavedPath).findFirst().orElse(null);
            default -> throw new IllegalArgumentException("Unsupported P2P archive provider");
        };
    }
    private String fallbackPath(String provider, MovieMetadata movie) {
        return switch (provider) {
            case "XUNLEI" -> {
                XunleiTransferTask task = new XunleiTransferTask(); task.setMovieId(movie.getId());
                yield XunleiTransferRunnerServiceImpl.transferPath(properties, task, title(movie));
            }
            case "QUARK" -> {
                String base = properties.getQuark().getSavePath();
                if (base == null || base.isBlank()) base = "/GYing Resource Hub";
                String category = "tv".equals(movie.getCategory()) ? "tv" : "ac".equals(movie.getCategory()) ? "anime" : "movie";
                String path = base.replaceAll("/+$", "") + "/" + category + "/" + safe(SeasonSearchUtils.baseTitle(title(movie))) + "（" + safe(movie.getId()) + "）";
                if (movie.getSeason() != null && movie.getSeason() > 0 && !"movie".equals(category)) path += "/" + safe(SeasonSearchUtils.seasonLabel(movie.getSeason()));
                yield path;
            }
            case "BAIDU" -> {
                String base = properties.getBaidu().getSavePath();
                if (base == null || base.isBlank()) base = "/GYing Resource Hub";
                yield base.replaceAll("/+$", "") + "/" + safe(title(movie)) + "（" + safe(movie.getId()) + "）";
            }
            default -> throw new IllegalArgumentException("Unsupported P2P archive provider");
        };
    }
    private boolean canRunArchive(String provider) {
        return properties.isEnabled() && properties.isDriveTorrentEnabled(provider) && properties.isDriveTransferEnabled(provider)
                && properties.isDriveShareEnabled(provider) && (!"BAIDU".equals(provider) || cloud.isConfigured(provider));
    }
    private static String requireProvider(String provider) {
        return switch (Objects.toString(provider, "")) {
            case "QUARK", "XUNLEI", "BAIDU" -> provider;
            default -> throw new IllegalArgumentException("Unsupported P2P archive provider");
        };
    }
    private static boolean usableOwnShare(String provider, String shareUrl) {
        if (shareUrl == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(shareUrl);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null) return false;
            return switch (provider) {
                case "QUARK" -> "pan.quark.cn".equals(uri.getHost()) && uri.getPath().startsWith("/s/") && uri.getPath().length() > 3;
                case "XUNLEI" -> "pan.xunlei.com".equals(uri.getHost()) && uri.getPath().startsWith("/s/") && uri.getPath().length() > 3;
                case "BAIDU" -> shareUrl.equals(BaiduWebClient.normalizedShareUrl(shareUrl, null));
                default -> false;
            };
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private void persist(ResourceHubTask task, Map<String, Object> payload) {
        task.setPayload(json(payload)); task.setUpdatedAt(LocalDateTime.now());
        // MyBatis skips null entity fields, so successful retries must explicitly clear the old error.
        boolean saved = task.getLastError() == null
                ? tasks.update(task, new UpdateWrapper<ResourceHubTask>().eq("id", task.getId()).set("last_error", null))
                : tasks.updateById(task);
        if (!saved) throw new IllegalStateException("P2P archive checkpoint was not persisted");
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
