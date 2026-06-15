package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.ResourceLinkMapper;
import com.gying.movie.service.IResourceLinkService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ResourceLinkServiceImpl extends ServiceImpl<ResourceLinkMapper, ResourceLink>
        implements IResourceLinkService {

    @Override
    public List<ResourceLink> getResourcesByMovieId(String movieId) {
        return list(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .eq("audit_status", 1)
                .eq("status", "ACTIVE")
                .ne("link_status", "INVALID"));
    }

    @Override
    public void addResource(ResourceLink resourceLink) {
        save(resourceLink);
    }
}
