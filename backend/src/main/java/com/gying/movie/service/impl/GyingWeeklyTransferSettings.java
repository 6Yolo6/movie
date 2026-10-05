package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.GyingWeeklyTransferSchedule;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.service.ISysConfigService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GyingWeeklyTransferSettings {
    static final String KEY = "resource.hub.gying.weekly_transfer_schedules";
    private static final List<String> TYPES = List.of("mv", "tv", "ac");
    private final ISysConfigService configs;
    private final ObjectMapper mapper;

    public GyingWeeklyTransferSettings(ISysConfigService configs, ObjectMapper mapper) {
        this.configs = configs;
        this.mapper = mapper;
    }

    public synchronized List<GyingWeeklyTransferSchedule> get() {
        SysConfig config = row();
        if (config == null) {
            List<GyingWeeklyTransferSchedule> defaults = new ArrayList<>();
            for (String type : TYPES) {
                var schedule = new GyingWeeklyTransferSchedule();
                schedule.setTypeCode(type);
                schedule.setNextRunAt(LocalDateTime.now().plusDays(7).toString());
                schedule.setLastStatus("NOT_STARTED");
                defaults.add(schedule);
            }
            save(defaults);
            return defaults;
        }
        try {
            var schedules = mapper.readValue(config.getConfigValue(),
                    new TypeReference<List<GyingWeeklyTransferSchedule>>() {});
            validate(schedules);
            for (var schedule : schedules) LocalDateTime.parse(schedule.getNextRunAt());
            return schedules;
        } catch (Exception e) {
            // Never reset a corrupt schedule to due-now and accidentally start cloud writes.
            throw new IllegalStateException("Invalid weekly transfer schedule; automatic execution stopped", e);
        }
    }

    public void validate(List<GyingWeeklyTransferSchedule> schedules) {
        if (schedules == null || schedules.size() != 3
                || schedules.stream().anyMatch(java.util.Objects::isNull)
                || !schedules.stream().map(GyingWeeklyTransferSchedule::getTypeCode)
                        .collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(TYPES))
                || schedules.stream().anyMatch(s -> s.getIntervalDays() < 1 || s.getIntervalDays() > 365
                        || s.getMaxItems() < 1 || s.getMaxItems() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "本周热门须包含电影/剧集/动漫；间隔 1–365 天，每轮 1–20 部");
        }
    }

    public synchronized void update(List<GyingWeeklyTransferSchedule> requested) {
        validate(requested);
        var current = get();
        for (var old : current) {
            var next = requested.stream().filter(s -> s.getTypeCode().equals(old.getTypeCode())).findFirst().orElseThrow();
            if (next.getIntervalDays() != old.getIntervalDays() || next.isEnabled() != old.isEnabled()) {
                old.setNextRunAt(LocalDateTime.now().plusDays(next.getIntervalDays()).toString());
            }
            old.setEnabled(next.isEnabled()); old.setIntervalDays(next.getIntervalDays()); old.setMaxItems(next.getMaxItems());
        }
        save(current); // Ignore caller-supplied timestamps, statuses and task IDs.
    }

    /** Claim before network writes; failures/restarts never retry every worker tick. */
    public synchronized GyingWeeklyTransferSchedule claimDue() {
        var all = get();
        var now = LocalDateTime.now();
        for (var s : all) {
            if (s.isEnabled() && !LocalDateTime.parse(s.getNextRunAt()).isAfter(now)) {
                s.setLastRunAt(now.toString()); s.setLastStatus("RUNNING");
                s.setNextRunAt(now.plusDays(s.getIntervalDays()).toString());
                save(all);
                return s;
            }
        }
        return null;
    }

    public synchronized void finished(String type, String status, Long taskId) {
        var all = get();
        for (var s : all) if (s.getTypeCode().equals(type)) {
            s.setLastStatus(status); s.setLastTaskId(taskId);
        }
        save(all);
    }

    private SysConfig row() { return configs.getOne(new QueryWrapper<SysConfig>().eq("config_key", KEY)); }

    private void save(List<GyingWeeklyTransferSchedule> schedules) {
        try {
            String value = mapper.writeValueAsString(schedules);
            SysConfig config = row();
            boolean created = config == null;
            if (created) { config = new SysConfig(); config.setConfigKey(KEY); }
            config.setConfigValue(value); config.setDescription("GYING 本周热门独立转存计划（电影/剧集/动漫）");
            if (!(created ? configs.save(config) : configs.updateById(config))) {
                throw new IllegalStateException("Weekly transfer schedule was not persisted");
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot save weekly transfer schedule", e); }
    }
}
