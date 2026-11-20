package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.BaiduTransferTask;
import com.gying.movie.mapper.BaiduTransferTaskMapper;
import com.gying.movie.service.IBaiduTransferTaskService;
import org.springframework.stereotype.Service;

@Service
public class BaiduTransferTaskServiceImpl extends ServiceImpl<BaiduTransferTaskMapper, BaiduTransferTask>
        implements IBaiduTransferTaskService { }
