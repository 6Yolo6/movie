package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.ResourceHubTask;
import java.util.Map;

public interface IResourceHubTaskService extends IService<ResourceHubTask> {
    ResourceHubTask enqueue(ResourceHubTask task);

    Map<String, Long> countByStatus();
}
