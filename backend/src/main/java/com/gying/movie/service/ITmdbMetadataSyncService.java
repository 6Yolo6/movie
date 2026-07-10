package com.gying.movie.service;

import com.gying.movie.dto.ResourceHubMetadataSyncRequest;
import com.gying.movie.dto.TmdbSyncResult;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceHubTask;

public interface ITmdbMetadataSyncService {
    ResourceHubTask enqueue(ResourceHubMetadataSyncRequest request);

    TmdbSyncResult runTask(Long taskId);

    MovieMetadata syncBestByKeyword(String keyword);
}
