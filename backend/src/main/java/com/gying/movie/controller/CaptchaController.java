package com.gying.movie.controller;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/generate")
    public Map<String, Object> generate() {
        // Generate a 4-digit alphanumeric captcha (60x30)
        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(60, 30, 4, 4);
        String captchaId = IdUtil.fastSimpleUUID();
        String code = captcha.getCode().toLowerCase();

        // Store code in Redis with 5-minute TTL
        String redisKey = "captcha:" + captchaId;
        stringRedisTemplate.opsForValue().set(redisKey, code, 5, TimeUnit.MINUTES);

        return Map.of(
                "captchaId", captchaId,
                "image", captcha.getImageBase64Data()
        );
    }
}
