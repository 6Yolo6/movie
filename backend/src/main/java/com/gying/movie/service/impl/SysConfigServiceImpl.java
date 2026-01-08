package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.SysConfig;
import com.gying.movie.mapper.SysConfigMapper;
import com.gying.movie.service.ISysConfigService;
import org.springframework.stereotype.Service;

@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements ISysConfigService {

    @Override
    public String getConfigValue(String key, String defaultValue) {
        SysConfig config = getOne(new QueryWrapper<SysConfig>().eq("config_key", key));
        return config != null ? config.getConfigValue() : defaultValue;
    }

    @Override
    public boolean updateConfig(String key, String value) {
        SysConfig config = getOne(new QueryWrapper<SysConfig>().eq("config_key", key));
        if (config != null) {
            config.setConfigValue(value);
            return updateById(config);
        }
        return false;
    }
}
