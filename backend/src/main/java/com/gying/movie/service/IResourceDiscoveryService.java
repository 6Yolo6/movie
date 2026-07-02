package com.gying.movie.service;

import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.entity.ResourceHubTask;

public interface IResourceDiscoveryService {
    ResourceHubTask enqueue(ResourceDiscoveryRequest request);

    ResourceDiscoveryRunResult runTask(Long taskId);
}
