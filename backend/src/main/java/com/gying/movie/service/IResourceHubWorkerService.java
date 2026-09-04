package com.gying.movie.service;

import com.gying.movie.dto.ResourceHubWorkerResult;
import java.util.Map;

public interface IResourceHubWorkerService {
    ResourceHubWorkerResult runOnce();

    ResourceHubWorkerResult runOnce(boolean force);

    Map<String, Object> retryDiscoveredTransfers(boolean force);

    boolean isRunning();
}
