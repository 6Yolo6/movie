package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.XunleiTransferTask;
import com.gying.movie.mapper.XunleiTransferTaskMapper;
import com.gying.movie.service.IXunleiTransferTaskService;
import org.springframework.stereotype.Service;

@Service
public class XunleiTransferTaskServiceImpl extends ServiceImpl<XunleiTransferTaskMapper, XunleiTransferTask>
        implements IXunleiTransferTaskService {
}
