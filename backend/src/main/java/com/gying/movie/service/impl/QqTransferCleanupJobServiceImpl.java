package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.QqTransferCleanupJob;
import com.gying.movie.mapper.QqTransferCleanupJobMapper;
import com.gying.movie.service.IQqTransferCleanupJobService;
import org.springframework.stereotype.Service;

@Service
public class QqTransferCleanupJobServiceImpl
        extends ServiceImpl<QqTransferCleanupJobMapper, QqTransferCleanupJob>
        implements IQqTransferCleanupJobService {
}
