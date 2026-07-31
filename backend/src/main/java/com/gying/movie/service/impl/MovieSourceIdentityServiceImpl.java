package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.MovieSourceIdentity;
import com.gying.movie.mapper.MovieSourceIdentityMapper;
import com.gying.movie.service.IMovieSourceIdentityService;
import org.springframework.stereotype.Service;

@Service
public class MovieSourceIdentityServiceImpl
        extends ServiceImpl<MovieSourceIdentityMapper, MovieSourceIdentity>
        implements IMovieSourceIdentityService {
}
