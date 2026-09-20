package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gying.movie.client.QuarkAutoSaveClient;
import com.gying.movie.client.XunleiClient;
import com.gying.movie.entity.QqTransferCleanupJob;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.service.IQqTransferCleanupJobService;
import com.gying.movie.service.IQuarkTransferTaskService;
import com.gying.movie.service.IResourceDiscoveryResultService;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.service.IXunleiTransferTaskService;
import com.gying.movie.utils.QqTransferMarker;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class QqTransferCleanupService {
    private static final Logger log = LoggerFactory.getLogger(QqTransferCleanupService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final QqTransferCleanupSettings settings;
    private final IQqTransferCleanupJobService jobService;
    private final IQuarkTransferTaskService quarkTaskService;
    private final IXunleiTransferTaskService xunleiTaskService;
    private final IResourceDiscoveryResultService discoveryService;
    private final IResourceLinkService resourceLinkService;
    private final QuarkAutoSaveClient quarkClient;
    private final XunleiClient xunleiClient;

    public QqTransferCleanupService(
            QqTransferCleanupSettings settings,
            IQqTransferCleanupJobService jobService,
            IQuarkTransferTaskService quarkTaskService,
            IXunleiTransferTaskService xunleiTaskService,
            IResourceDiscoveryResultService discoveryService,
            IResourceLinkService resourceLinkService,
            QuarkAutoSaveClient quarkClient,
            XunleiClient xunleiClient) {
        this.settings = settings;
        this.jobService = jobService;
        this.quarkTaskService = quarkTaskService;
        this.xunleiTaskService = xunleiTaskService;
        this.discoveryService = discoveryService;
        this.resourceLinkService = resourceLinkService;
        this.quarkClient = quarkClient;
        this.xunleiClient = xunleiClient;
    }

    public void prepareTemporaryTask(QuarkTransferTask task) {
        if (task == null || task.getId() == null) return;
        task.setRequestPayload(QqTransferMarker.payload(settings.quarkTarget(task.getId())));
        task.setUpdatedAt(LocalDateTime.now());
        if (!quarkTaskService.updateById(task)) {
            throw new IllegalStateException("Failed to mark QQ Quark transfer as temporary");
        }
    }

    public void prepareTemporaryTask(XunleiTransferTask task) {
        if (task == null || task.getId() == null) return;
        task.setRequestPayload(QqTransferMarker.payload(settings.xunleiTarget(task.getId())));
        task.setUpdatedAt(LocalDateTime.now());
        if (!xunleiTaskService.updateById(task)) {
            throw new IllegalStateException("Failed to mark QQ Xunlei transfer as temporary");
        }
    }

    public void scheduleForDiscovery(Long discoveryId, Long resourceLinkId) {
        if (!settings.enabled() || discoveryId == null || resourceLinkId == null) return;
        XunleiTransferTask xunlei = xunleiTaskService.getOne(new QueryWrapper<XunleiTransferTask>()
                .eq("discovery_result_id", discoveryId)
                .like("request_payload", "QQ_BOT")
                .orderByDesc("updated_at").last("LIMIT 1"), false);
        String xunleiTarget = xunlei == null ? null : QqTransferMarker.targetPath(xunlei.getRequestPayload());
        if (xunlei != null && "SUCCEEDED".equalsIgnoreCase(xunlei.getStatus())
                && QqTransferMarker.isTemporary(xunlei.getRequestPayload())
                && settings.isUnderXunleiRoot(xunleiTarget)) {
            schedule("XUNLEI", xunlei.getId(), discoveryId, xunlei.getMovieId(),
                    xunlei.getSavedPath(), xunleiTarget, resourceLinkId);
            return;
        }
        QuarkTransferTask quark = quarkTaskService.getOne(new QueryWrapper<QuarkTransferTask>()
                .eq("discovery_result_id", discoveryId)
                .like("request_payload", "QQ_BOT")
                .orderByDesc("updated_at").last("LIMIT 1"), false);
        String quarkTarget = quark == null ? null : QqTransferMarker.targetPath(quark.getRequestPayload());
        if (quark != null && ("SUBMITTED".equalsIgnoreCase(quark.getStatus())
                || "SUCCEEDED".equalsIgnoreCase(quark.getStatus())) && quark.getShareUrl() != null
                && QqTransferMarker.isTemporary(quark.getRequestPayload())
                && settings.isUnderQuarkRoot(quarkTarget)) {
            schedule("QUARK", quark.getId(), discoveryId, quark.getMovieId(),
                    quark.getSavedPath(), quarkTarget, resourceLinkId);
        }
    }

    private void schedule(String provider, Long transferTaskId, Long discoveryId, String movieId,
            String savedPath, String targetPath, Long resourceLinkId) {
        if (savedPath == null || savedPath.isBlank() || targetPath == null || targetPath.isBlank()) return;
        boolean safeTarget = "QUARK".equalsIgnoreCase(provider)
                ? settings.isUnderQuarkRoot(targetPath)
                : "XUNLEI".equalsIgnoreCase(provider) && settings.isUnderXunleiRoot(targetPath);
        if (!safeTarget) return;
        QqTransferCleanupJob existing = jobService.getOne(new QueryWrapper<QqTransferCleanupJob>()
                .eq("provider", provider).eq("transfer_task_id", transferTaskId).last("LIMIT 1"), false);
        LocalDateTime now = LocalDateTime.now();
        QqTransferCleanupJob job = existing == null ? new QqTransferCleanupJob() : existing;
        job.setProvider(provider);
        job.setTransferTaskId(transferTaskId);
        job.setDiscoveryResultId(discoveryId);
        job.setMovieId(movieId);
        job.setSavedPath(savedPath);
        job.setTargetPath(targetPath);
        job.setResourceLinkId(resourceLinkId);
        job.setStatus("PENDING");
        job.setAttempts(0);
        job.setLastError(null);
        job.setDeleteAfter(now.plusMinutes(settings.delayMinutes()));
        job.setUpdatedAt(now);
        job.setFinishedAt(null);
        if (existing == null) {
            job.setCreatedAt(now);
            jobService.save(job);
        } else {
            jobService.updateById(job);
        }
    }

    @Scheduled(fixedDelayString = "${qq-bot.transfer-cleanup-check-ms:60000}")
    public void cleanupDueTransfers() {
        if (!settings.enabled()) return;
        List<QqTransferCleanupJob> jobs = jobService.list(new QueryWrapper<QqTransferCleanupJob>()
                .in("status", List.of("PENDING", "FAILED"))
                .le("delete_after", LocalDateTime.now())
                .lt("attempts", MAX_ATTEMPTS)
                .orderByAsc("delete_after")
                .last("LIMIT 20"));
        for (QqTransferCleanupJob job : jobs) {
            cleanup(job);
        }
    }

    private void cleanup(QqTransferCleanupJob job) {
        LocalDateTime now = LocalDateTime.now();
        job.setStatus("RUNNING");
        job.setAttempts(job.getAttempts() == null ? 1 : job.getAttempts() + 1);
        job.setUpdatedAt(now);
        jobService.updateById(job);
        try {
            if ("QUARK".equalsIgnoreCase(job.getProvider())) {
                QuarkTransferTask task = quarkTaskService.getById(job.getTransferTaskId());
                requireTemporary(task == null ? null : task.getRequestPayload(), job.getTargetPath(), true);
                quarkClient.deletePath(job.getSavedPath());
            } else if ("XUNLEI".equalsIgnoreCase(job.getProvider())) {
                XunleiTransferTask task = xunleiTaskService.getById(job.getTransferTaskId());
                requireTemporary(task == null ? null : task.getRequestPayload(), job.getTargetPath(), false);
                xunleiClient.trashFile(job.getSavedPath());
            } else {
                throw new IllegalStateException("Unsupported QQ cleanup provider");
            }
            invalidateTemporaryResource(job);
            job.setStatus("SUCCEEDED");
            job.setLastError(null);
            job.setFinishedAt(LocalDateTime.now());
        } catch (Exception error) {
            job.setStatus("FAILED");
            job.setLastError(trim(error.getMessage(), 1000));
            log.warn("QQ temporary transfer cleanup failed for job {}", job.getId());
        }
        job.setUpdatedAt(LocalDateTime.now());
        jobService.updateById(job);
    }

    private void requireTemporary(String marker, String targetPath, boolean quark) {
        if (!QqTransferMarker.isTemporary(marker)) {
            throw new IllegalStateException("Cleanup refused: transfer is not marked as QQ temporary");
        }
        String markedPath = QqTransferMarker.targetPath(marker);
        if (markedPath == null || !markedPath.equals(targetPath)) {
            throw new IllegalStateException("Cleanup refused: temporary target path does not match marker");
        }
        boolean safe = quark ? settings.isUnderQuarkRoot(targetPath) : settings.isUnderXunleiRoot(targetPath);
        if (!safe) {
            throw new IllegalStateException("Cleanup refused: target path is outside QQ temporary root");
        }
    }

    private void invalidateTemporaryResource(QqTransferCleanupJob job) {
        LocalDateTime now = LocalDateTime.now();
        ResourceLink link = resourceLinkService.getById(job.getResourceLinkId());
        if (link != null) {
            link.setStatus("DELETED");
            link.setLinkStatus("INVALID");
            link.setDeletedAt(now);
            link.setValidatedAt(now);
            link.setLastCheckError("QQ 群临时转存已按计划清理");
            link.setUpdatedAt(now);
            resourceLinkService.updateById(link);
        }
        ResourceDiscoveryResult discovery = discoveryService.getById(job.getDiscoveryResultId());
        if (discovery != null) {
            discovery.setStatus("IGNORED");
            discovery.setFailureReason("QQ 群临时转存已按计划清理");
            discovery.setUpdatedAt(now);
            discoveryService.updateById(discovery);
        }
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) return "未知错误";
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
