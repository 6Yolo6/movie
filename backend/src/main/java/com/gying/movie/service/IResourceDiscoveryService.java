package com.gying.movie.service;

import com.gying.movie.dto.ResourceDiscoveryRequest;
import com.gying.movie.dto.ResourceDiscoveryRunResult;
import com.gying.movie.entity.ResourceHubTask;

public interface IResourceDiscoveryService {
    ResourceHubTask enqueue(ResourceDiscoveryRequest request);

    ResourceDiscoveryRunResult runTask(Long taskId);

    /**
     * Ensures that a user-selected discovery result has exactly one provider
     * transfer task associated with it. This is intentionally a single-result
     * operation; it must never enqueue a batch for the whole movie.
     */
    boolean ensureTransferTask(Long discoveryResultId);
}
