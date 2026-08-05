package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.SocialPostLog;
import com.gying.movie.mapper.SocialPostLogMapper;
import com.gying.movie.service.ISocialPostLogService;
import org.springframework.stereotype.Service;

@Service
public class SocialPostLogServiceImpl
        extends ServiceImpl<SocialPostLogMapper, SocialPostLog>
        implements ISocialPostLogService {
}
