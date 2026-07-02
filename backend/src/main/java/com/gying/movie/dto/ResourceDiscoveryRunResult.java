package com.gying.movie.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ResourceDiscoveryRunResult {
    private Long taskId;
    private String movieId;
    private String source;
    private String keyword;
    private int discovered;
    private int duplicate;
    private int transferTasksCreated;
    private int failed;
    private String status;
    private List<String> errors = new ArrayList<>();
}
