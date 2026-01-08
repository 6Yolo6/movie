package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.MovieMetadata;

public interface IMovieMetadataService extends IService<MovieMetadata> {
    MovieMetadata getMovieWithResources(String id);
}
