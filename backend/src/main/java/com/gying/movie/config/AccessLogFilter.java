package com.gying.movie.config;

import com.gying.movie.service.impl.MonitoringService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AccessLogFilter extends OncePerRequestFilter {
    private final MonitoringService monitoring;
    public AccessLogFilter(MonitoringService monitoring){this.monitoring=monitoring;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String p=request.getRequestURI();return !p.startsWith("/api/")||p.startsWith("/api/internal/")||p.equals("/api/monitoring/page-view");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{long start=System.nanoTime();try{chain.doFilter(req,res);}finally{monitoring.access(req.getRequestURI(),req.getMethod(),res.getStatus(),(System.nanoTime()-start)/1_000_000,req,"API");}}
}
