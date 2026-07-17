package com.gying.movie.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TmdbSyncResult {
    private Long taskId;
    private String source;
    private int page;
    private int requested;
    private int processed;
    private int inserted;
    private int updated;
    private int skipped;
    private int failed;
    private int discoveryTasksCreated;
    private int discoveryTasksSkipped;
    private String status;
    private List<String> errors = new ArrayList<>();
}
