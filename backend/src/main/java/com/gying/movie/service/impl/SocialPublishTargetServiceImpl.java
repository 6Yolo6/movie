package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.SocialPublishTarget;
import com.gying.movie.mapper.SocialPublishTargetMapper;
import com.gying.movie.service.ISocialPublishTargetService;
import org.springframework.stereotype.Service;

@Service
public class SocialPublishTargetServiceImpl
        extends ServiceImpl<SocialPublishTargetMapper, SocialPublishTarget>
        implements ISocialPublishTargetService {
}
