package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.ResourceLink;
import java.util.List;

public interface IResourceLinkService extends IService<ResourceLink> {
    List<ResourceLink> getResourcesByMovieId(String movieId);

    void addResource(ResourceLink resourceLink);
}
