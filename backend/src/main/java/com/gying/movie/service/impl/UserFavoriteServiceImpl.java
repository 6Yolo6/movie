package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.UserFavorite;
import com.gying.movie.mapper.UserFavoriteMapper;
import com.gying.movie.service.IUserFavoriteService;
import org.springframework.stereotype.Service;

@Service
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements IUserFavoriteService {
}
