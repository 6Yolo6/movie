package com.gying.movie.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gying.movie.entity.ResourceReport;
import com.gying.movie.mapper.ResourceReportMapper;
import com.gying.movie.service.IResourceReportService;
import org.springframework.stereotype.Service;

@Service
public class ResourceReportServiceImpl extends ServiceImpl<ResourceReportMapper, ResourceReport>
        implements IResourceReportService {
}