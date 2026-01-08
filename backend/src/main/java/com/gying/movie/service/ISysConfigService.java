package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.SysConfig;

public interface ISysConfigService extends IService<SysConfig> {

    /**
     * Get configuration value by key
     * 
     * @param key          Configuration key
     * @param defaultValue Default value if key not found
     * @return Configuration value
     */
    String getConfigValue(String key, String defaultValue);

    /**
     * Update configuration value
     * 
     * @param key   Configuration key
     * @param value New value
     * @return true if update successful
     */
    boolean updateConfig(String key, String value);
}
