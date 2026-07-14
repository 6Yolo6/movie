package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.QqBotSearchLog;
import com.gying.movie.mapper.QqBotSearchLogMapper;
import com.gying.movie.service.IQqBotSearchLogService;
import org.springframework.stereotype.Service;

@Service
public class QqBotSearchLogServiceImpl extends ServiceImpl<QqBotSearchLogMapper, QqBotSearchLog>
        implements IQqBotSearchLogService {
}
