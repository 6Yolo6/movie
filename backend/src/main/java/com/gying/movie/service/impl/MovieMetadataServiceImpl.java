package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.mapper.MovieMetadataMapper;
import com.gying.movie.service.IMovieMetadataService;
import org.springframework.stereotype.Service;

@Service
public class MovieMetadataServiceImpl extends ServiceImpl<MovieMetadataMapper, MovieMetadata>
        implements IMovieMetadataService {

    @Override
    public MovieMetadata getMovieWithResources(String id) {
        // Logic to get movie details. Resources will be fetched separately or joined
        // depending on API design.
        // For now, basic retrieval.
        return this.getById(id);
    }
}
