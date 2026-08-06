package com.gying.movie.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SocialPublishingScheduler {
    private static final Logger log = LoggerFactory.getLogger(SocialPublishingScheduler.class);
    private final SocialPublishingService service;

    public SocialPublishingScheduler(SocialPublishingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${social-publisher.scheduler-delay-ms:60000}")
    public void run() {
        try {
            service.runDueTargets();
        } catch (Exception error) {
            log.warn("Social publishing scheduler failed", error);
        }
    }
}
