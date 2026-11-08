package com.gying.movie.service;

import com.gying.movie.dto.QuarkTransferRunResult;

public interface IBaiduTransferRunnerService {
    QuarkTransferRunResult submitPending(int limit);
    QuarkTransferRunResult submitOne(Long taskId);
}
