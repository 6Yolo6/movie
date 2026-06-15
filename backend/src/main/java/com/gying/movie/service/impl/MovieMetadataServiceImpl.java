package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.mapper.MovieMetadataMapper;
import com.gying.movie.service.IMovieMetadataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class MovieMetadataServiceImpl extends ServiceImpl<MovieMetadataMapper, MovieMetadata>
        implements IMovieMetadataService {

    @Autowired
    private MovieMetadataMapper movieMetadataMapper;

    @Override
    public MovieMetadata getMovieWithResources(String id) {
        return this.getById(id);
    }

    @Override
    public List<String> getDistinctStrings(String column) {
        return getDistinctStrings(column, null);
    }

    @Override
    public List<String> getDistinctStrings(String column, String category) {
        Set<String> allowedColumns = Set.of("genres", "regions", "languages");
        if (!allowedColumns.contains(column)) {
            throw new IllegalArgumentException("Unsupported filter column: " + column);
        }
        return movieMetadataMapper.selectDistinctJsonValues(column, category);
    }

    @Override
    public List<String> getDistinctYears() {
        return getDistinctYears(null);
    }

    @Override
    public List<String> getDistinctYears(String category) {
        return movieMetadataMapper.selectDistinctYears(category);
    }
}
