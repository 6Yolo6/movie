package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.QuarkTransferTask;
import com.gying.movie.mapper.QuarkTransferTaskMapper;
import com.gying.movie.service.IQuarkTransferTaskService;
import org.springframework.stereotype.Service;

@Service
public class QuarkTransferTaskServiceImpl extends ServiceImpl<QuarkTransferTaskMapper, QuarkTransferTask>
        implements IQuarkTransferTaskService {
}
