package com.gying.movie.dto;

import com.gying.movie.entity.MovieMetadata;
import com.gying.movie.entity.ResourceLink;
import lombok.Data;
import java.util.List;

@Data
public class MovieDetailDTO {
    private MovieMetadata movie;
    private List<ResourceLink> resources;
}
