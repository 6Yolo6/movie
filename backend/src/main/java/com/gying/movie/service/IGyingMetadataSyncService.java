package com.gying.movie.service;

import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.ResourceHubTask;

public interface IGyingMetadataSyncService {
    ResourceHubTask enqueue(String source, int page, int maxItems);

    TmdbSyncResult runTask(Long taskId);
}
