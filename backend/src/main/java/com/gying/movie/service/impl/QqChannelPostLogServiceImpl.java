package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.QqChannelPostLog;
import com.gying.movie.mapper.QqChannelPostLogMapper;
import com.gying.movie.service.IQqChannelPostLogService;
import org.springframework.stereotype.Service;

@Service
public class QqChannelPostLogServiceImpl extends ServiceImpl<QqChannelPostLogMapper, QqChannelPostLog>
        implements IQqChannelPostLogService {
}
