package com.gying.movie.service;

import com.gying.movie.dto.ResourceHubPublishResult;

public interface IResourceHubPublishService {
    ResourceHubPublishResult publishDiscovery(Long discoveryResultId);

    ResourceHubPublishResult publishPending(int limit);
}
