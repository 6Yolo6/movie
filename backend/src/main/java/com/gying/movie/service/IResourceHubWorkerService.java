package com.gying.movie.service;

import com.gying.movie.dto.ResourceHubWorkerResult;

public interface IResourceHubWorkerService {
    ResourceHubWorkerResult runOnce();

    ResourceHubWorkerResult runOnce(boolean force);

    boolean isRunning();
}
