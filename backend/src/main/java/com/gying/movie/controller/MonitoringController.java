package com.gying.movie.controller;

import com.gying.movie.service.impl.MonitoringService;
import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonitoringController {
    private final MonitoringService monitoring; private final AuthHelper auth;
    public MonitoringController(MonitoringService monitoring,AuthHelper auth){this.monitoring=monitoring;this.auth=auth;}
    @PostMapping("/api/monitoring/page-view") public Map<String,Object> pageView(@RequestBody(required=false) Map<String,String> body,HttpServletRequest request){String path=body==null?"/":body.getOrDefault("path","/");monitoring.access(path,"VIEW",200,0,request,"PAGE_VIEW");return Map.of("ok",true);}
    @PostMapping("/api/monitoring/resource-operation") public Map<String,Object> resourceOperation(@RequestBody Map<String,Object> body,HttpServletRequest request){Long id=body.get("resourceLinkId") instanceof Number n?n.longValue():null;monitoring.resourceOperation(String.valueOf(body.getOrDefault("movieId","")),id,String.valueOf(body.getOrDefault("operationType","VIEW")),String.valueOf(body.getOrDefault("provider","")),"SUCCESS",null,request);return Map.of("ok",true);}
    @GetMapping("/api/admin/monitoring/overview") public Map<String,Object> overview(@RequestHeader(value="Authorization",required=false)String token){auth.requireAdmin(token);return monitoring.overview();}
    @GetMapping("/api/admin/monitoring/logs") public Map<String,Object> logs(@RequestHeader(value="Authorization",required=false)String token,@RequestParam(required=false)String q,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){auth.requireAdmin(token);return monitoring.logs(q,page,size);}
    @GetMapping("/api/admin/monitoring/hot-searches") public Object hot(@RequestHeader(value="Authorization",required=false)String token,@RequestParam(required=false)LocalDate date,@RequestParam(defaultValue="20")int limit){auth.requireAdmin(token);return monitoring.hotSearches(date==null?LocalDate.now():date,limit);}
}
