package com.gying.movie.controller;

import com.gying.movie.dto.AuthUser;
import com.gying.movie.entity.SysUser;
import com.gying.movie.service.ISysConfigService;
import com.gying.movie.service.ISysUserService;
import com.gying.movie.service.impl.RegistrationService;
import com.gying.movie.utils.AuthHelper;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/invitations")
public class InvitationController {
    private final AuthHelper auth;
    private final ISysUserService users;
    private final ISysConfigService config;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public InvitationController(AuthHelper auth, ISysUserService users, ISysConfigService config, JdbcTemplate jdbc) {
        this.auth = auth; this.users = users; this.config = config; this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> mine(@RequestHeader(value="Authorization", required=false) String token) {
        AuthUser current = auth.requireUser(token);
        Eligibility eligibility = eligibility(current);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT id, code_hint, max_uses, used_count, expires_at, status, created_at FROM user_invitation_code WHERE creator_user_id=? ORDER BY id DESC LIMIT 50", current.getId());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("eligible", eligibility.eligible()); result.put("remaining", eligibility.remaining()); result.put("periodStart", eligibility.periodStart()); result.put("items", items);
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value="Authorization", required=false) String token) {
        AuthUser current = auth.requireUser(token);
        Eligibility e = eligibility(current);
        if (!e.eligible()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not yet eligible to create invitations");
        if (e.remaining() <= 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Invitation quota exhausted for this period");
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int maxUses = integer("auth.invite.default_max_uses", 1, 1, 20);
        int expireDays = integer("auth.invite.expire_days", 14, 1, 365);
        String hint = code.substring(0, 4) + "…" + code.substring(code.length() - 4);
        jdbc.update("INSERT INTO user_invitation_code(code_hash,code_hint,creator_user_id,max_uses,period_start,expires_at) VALUES (?,?,?,?,?,?)",
                RegistrationService.sha256(code), hint, current.getId(), maxUses, e.periodStart(), LocalDateTime.now().plusDays(expireDays));
        return Map.of("code", code, "invitePath", "/register?invite=" + code, "expiresAt", LocalDateTime.now().plusDays(expireDays), "maxUses", maxUses);
    }

    @DeleteMapping("/{id}")
    public Map<String,Object> revoke(@PathVariable long id, @RequestHeader(value="Authorization", required=false) String token) {
        AuthUser current=auth.requireUser(token);
        int changed=jdbc.update("UPDATE user_invitation_code SET status='REVOKED' WHERE id=? AND creator_user_id=? AND status='ACTIVE'", id,current.getId());
        return Map.of("revoked",changed>0);
    }

    private Eligibility eligibility(AuthUser current) {
        if (!Boolean.parseBoolean(config.getConfigValue("auth.invite.enabled","true"))) return new Eligibility(false,0,LocalDate.now());
        SysUser user=users.getById(current.getId());
        int minAge=integer("auth.invite.min_account_age_days",30,0,3650);
        int periodDays=integer("auth.invite.period_days",30,1,365);
        int max=integer("auth.invite.max_per_period",3,1,100);
        LocalDate today=LocalDate.now();
        long epoch=today.toEpochDay();
        LocalDate periodStart=LocalDate.ofEpochDay(epoch-Math.floorMod(epoch,periodDays));
        boolean oldEnough="ADMIN".equalsIgnoreCase(current.getRole()) || (user!=null && user.getCreatedAt()!=null && ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(),today)>=minAge);
        Integer used=jdbc.queryForObject("SELECT COUNT(*) FROM user_invitation_code WHERE creator_user_id=? AND period_start>=?",Integer.class,current.getId(),periodStart);
        return new Eligibility(oldEnough,Math.max(0,max-(used==null?0:used)),periodStart);
    }
    private int integer(String key,int fallback,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(config.getConfigValue(key,String.valueOf(fallback)))));}catch(Exception e){return fallback;}}
    private record Eligibility(boolean eligible,int remaining,LocalDate periodStart){}
}
