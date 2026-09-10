package com.gying.movie.service.impl;

import com.gying.movie.service.IResourceHubWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResourceHubScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResourceHubScheduler.class);

    private final IResourceHubWorkerService workerService;

    public ResourceHubScheduler(IResourceHubWorkerService workerService) {
        this.workerService = workerService;
    }

    @Scheduled(fixedDelayString = "${resource-hub.worker.fixed-delay-ms:60000}")
    public void run() {
        try {
            workerService.runOnce();
        } catch (Exception e) {
            log.warn("Resource Hub worker run failed", e);
        }
    }

    @Scheduled(cron = "${resource-hub.worker.discovered-retry-cron:0 30 8 * * *}", zone = "Asia/Shanghai")
    public void retryDiscoveredTransfers() {
        try {
            workerService.retryDiscoveredTransfers(false);
        } catch (Exception e) {
            log.warn("Discovered transfer retry run failed", e);
        }
    }
}
