package com.gying.movie.service;

import com.gying.movie.entity.QuarkTransferTask;

public interface IQuarkShareService {
    String ensureShareUrl(QuarkTransferTask task);
}
