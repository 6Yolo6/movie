package com.gying.movie.config;

import com.gying.movie.utils.AuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final AuthHelper auth;
    public WebConfig(@Value("${app.cors.allowed-origin:http://localhost:3000}") String allowedOrigin, AuthHelper auth) {
        this.allowedOrigins = Arrays.stream(allowedOrigin.split(",")).map(String::trim).toArray(String[]::new);
        this.auth = auth;
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Request-ID")
                .exposedHeaders("Retry-After", "X-Request-ID")
                .allowCredentials(false).maxAge(3600);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        // Defense in depth, including the legacy resource-review paths outside /api/admin.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!"OPTIONS".equals(request.getMethod())) auth.requireAdmin(request.getHeader("Authorization"));
                return true;
            }
        }).addPathPatterns("/api/admin/**", "/api/resources/admin/**", "/api/resources/*/audit");
    }
}
