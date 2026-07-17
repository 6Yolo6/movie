package com.gying.movie.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ResourceHubPublishResult {
    private Long discoveryResultId;
    private int published;
    private int updated;
    private int duplicate;
    private int skipped;
    private int failed;
    private List<Long> resourceIds = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
}
