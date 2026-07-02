package com.gying.movie.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuarkTransferRunResult {
    private Long taskId;
    private int submitted;
    private int skipped;
    private int failed;
    private List<String> errors = new ArrayList<>();
}
