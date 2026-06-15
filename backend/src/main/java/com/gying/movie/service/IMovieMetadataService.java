package com.gying.movie.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gying.movie.entity.MovieMetadata;

import java.util.List;

public interface IMovieMetadataService extends IService<MovieMetadata> {
    MovieMetadata getMovieWithResources(String id);
    List<String> getDistinctStrings(String column);
    List<String> getDistinctStrings(String column, String category);
    List<String> getDistinctYears();
    List<String> getDistinctYears(String category);
}
