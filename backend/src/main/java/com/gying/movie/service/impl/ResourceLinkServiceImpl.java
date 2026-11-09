package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.ResourceLink;
import com.gying.movie.mapper.ResourceLinkMapper;
import com.gying.movie.service.IResourceLinkService;
import com.gying.movie.utils.GyingTorrentLinks;
import com.gying.movie.utils.BaiduTemporaryScope;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ResourceLinkServiceImpl extends ServiceImpl<ResourceLinkMapper, ResourceLink>
        implements IResourceLinkService {

    @Override
    public List<ResourceLink> getResourcesByMovieId(String movieId) {
        return list(new QueryWrapper<ResourceLink>()
                .eq("movie_id", movieId)
                .and(query -> query.isNull("source").or().ne("source", BaiduTemporaryScope.RESOURCE_SOURCE))
                .eq("audit_status", 1)
                .eq("status", "ACTIVE")
                .isNull("deleted_at")
                .and(query -> query.isNull("link_status").or().ne("link_status", "INVALID")))
                .stream()
                .filter(link -> link != null && !BaiduTemporaryScope.RESOURCE_SOURCE.equalsIgnoreCase(link.getSource()))
                .filter(link -> !GyingTorrentLinks.isAccountDownload(link.getUrl())).toList();
    }

    @Override
    public void addResource(ResourceLink resourceLink) {
        save(resourceLink);
    }
}
