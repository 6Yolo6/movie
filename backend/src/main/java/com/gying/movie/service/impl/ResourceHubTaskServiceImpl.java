package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.ResourceHubTask;
import com.gying.movie.mapper.ResourceHubTaskMapper;
import com.gying.movie.service.IResourceHubTaskService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ResourceHubTaskServiceImpl extends ServiceImpl<ResourceHubTaskMapper, ResourceHubTask>
        implements IResourceHubTaskService {

    private static final List<String> KNOWN_STATUSES = List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED");

    @Override
    public ResourceHubTask enqueue(ResourceHubTask task) {
        LocalDateTime now = LocalDateTime.now();
        if (task.getStatus() == null || task.getStatus().isBlank()) {
            task.setStatus("PENDING");
        }
        if (task.getPriority() == null) {
            task.setPriority(0);
        }
        if (task.getAttempts() == null) {
            task.setAttempts(0);
        }
        if (task.getMaxAttempts() == null) {
            task.setMaxAttempts(3);
        }
        if (task.getScheduledAt() == null) {
            task.setScheduledAt(now);
        }
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        save(task);
        return task;
    }

    @Override
    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : KNOWN_STATUSES) {
            result.put(status, count(new QueryWrapper<ResourceHubTask>().eq("status", status)));
        }
        return result;
    }
}
