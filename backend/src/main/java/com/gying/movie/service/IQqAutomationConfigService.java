package com.gying.movie.service;

import java.util.Map;

public interface IQqAutomationConfigService {
    Map<String, Object> getConfig();

    Map<String, Object> updateConfig(Map<String, Object> request);

    Map<String, Object> reload();
}
