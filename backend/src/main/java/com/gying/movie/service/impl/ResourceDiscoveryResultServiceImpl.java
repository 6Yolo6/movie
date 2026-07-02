package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.ResourceDiscoveryResult;
import com.gying.movie.mapper.ResourceDiscoveryResultMapper;
import com.gying.movie.service.IResourceDiscoveryResultService;
import org.springframework.stereotype.Service;

@Service
public class ResourceDiscoveryResultServiceImpl extends ServiceImpl<ResourceDiscoveryResultMapper, ResourceDiscoveryResult>
        implements IResourceDiscoveryResultService {
}
