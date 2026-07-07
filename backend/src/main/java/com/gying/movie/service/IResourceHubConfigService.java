package com.gying.movie.service;

import com.gying.movie.dto.ResourceHubConfigRequest;
import com.gying.movie.dto.ResourceHubConfigResponse;

public interface IResourceHubConfigService {
    ResourceHubConfigResponse reload();

    ResourceHubConfigResponse getConfig();

    ResourceHubConfigResponse updateConfig(ResourceHubConfigRequest request);
}
