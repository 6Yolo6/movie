package com.gying.movie.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gying.movie.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiSecurityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilter.class);
    private static final long JSON_LIMIT = 1024 * 1024;
    private final RedisRateLimiter limiter;
    private final ClientIpResolver ips;
    private final ObjectMapper mapper;
    private final int perMinute;
    private final int globalPerMinute;

    public ApiSecurityFilter(RedisRateLimiter limiter, ClientIpResolver ips, ObjectMapper mapper,
            @Value("${app.security.api-per-minute:120}") int perMinute,
            @Value("${app.security.global-per-minute:1200}") int globalPerMinute) {
        this.limiter = limiter; this.ips = ips; this.mapper = mapper;
        this.perMinute = perMinute; this.globalPerMinute = globalPerMinute;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("Cache-Control", "no-store");
        request.setAttribute("security.requestId", requestId);
        String client = ips.resolve(request);
        request.setAttribute(ClientIpResolver.ATTRIBUTE, client);
        try {
            validateQuery(request);
            boolean multipart = request.getContentType() != null
                    && request.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/");
            long bodyLimit = multipart ? 9 * JSON_LIMIT : JSON_LIMIT;
            if (request.getContentLengthLong() > bodyLimit) throw new BodyTooLargeException();
            limiter.require("api-client", client, perMinute, Duration.ofMinutes(1));
            String category = category(request);
            int limit = switch (category) {
                case "auth" -> 10;
                case "search" -> 30;
                case "write" -> 20;
                case "admin" -> 60;
                case "internal" -> 120;
                default -> perMinute;
            };
            if (!"read".equals(category)) limiter.require("api-" + category, client, limit, Duration.ofMinutes(1));
            limiter.require("api-global", "all", globalPerMinute, Duration.ofMinutes(1));
            chain.doFilter(new LimitedRequest(request, bodyLimit), response);
        } catch (RedisRateLimiter.RateLimitExceededException error) {
            response.setHeader("Retry-After", Long.toString(error.retryAfterSeconds()));
            reject(response, 429, "TOO_MANY_REQUESTS", "Too many requests; try again later");
        } catch (BodyTooLargeException error) {
            reject(response, 413, "PAYLOAD_TOO_LARGE", "Request body is too large");
        } catch (ResponseStatusException error) {
            reject(response, error.getStatusCode().value(), "REQUEST_REJECTED",
                    error.getStatusCode().value() >= 500 ? "Service temporarily unavailable" : error.getReason());
        } finally {
            if (response.getStatus() >= 400) {
                // Do not persist every rejected request to MySQL or log supplied URLs/identities/secrets.
                log.warn("security_event=api_rejected status={} request_id={}", response.getStatus(), requestId);
            }
        }
    }
    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
    private static String path(HttpServletRequest request) {
        // Match MVC's decoded/matrix-parameter-stripped route, not attacker-controlled raw URI spelling.
        return org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
    static String category(HttpServletRequest request) {
        String path = path(request);
        if (path.startsWith("/api/auth/") && !"GET".equals(request.getMethod())) return "auth";
        if (path.startsWith("/api/admin/") || path.startsWith("/api/resources/admin") || path.endsWith("/audit")) return "admin";
        if (path.startsWith("/api/internal/") || path.startsWith("/api/qq-bot/")) return "internal";
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) return "write";
        if (path.startsWith("/api/movies") || path.contains("search") || path.contains("tmdb")
                || path.endsWith("bind-candidates") || path.startsWith("/api/captcha/")) return "search";
        return "read";
    }
    static void validateQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null && query.length() > 4096) badParameter();
        for (String name : new String[]{"page", "size", "pageSize", "limit"}) {
            String[] values = request.getParameterValues(name);
            if (values == null) continue;
            if (values.length != 1 || !values[0].matches("[0-9]{1,6}")) badParameter();
            int value = Integer.parseInt(values[0]);
            int max = "page".equals(name) ? 1000 : 100;
            if (value < 1 || value > max) badParameter();
        }
        for (String name : new String[]{"keyword", "q", "search"}) {
            String[] values = request.getParameterValues(name);
            if (values != null && (values.length != 1 || values[0].length() > 100)) badParameter();
        }
    }
    private static void badParameter() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request parameter is outside the allowed bounds");
    }
    public static class BodyTooLargeException extends RuntimeException { }
    private static class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximum;
        private ServletInputStream stream;
        LimitedRequest(HttpServletRequest request, long maximum) { super(request); this.maximum = maximum; }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (stream != null) return stream;
            ServletInputStream original = super.getInputStream();
            stream = new ServletInputStream() {
                private long count;
                private void count(int amount) { if (amount > 0 && (count += amount) > maximum) throw new BodyTooLargeException(); }
                @Override public int read() throws IOException { int value = original.read(); count(value < 0 ? 0 : 1); return value; }
                @Override public int read(byte[] bytes, int off, int len) throws IOException {
                    int n = original.read(bytes, off, (int) Math.min(len, maximum - count + 1)); count(n); return n;
                }
                @Override public boolean isFinished() { return original.isFinished(); }
                @Override public boolean isReady() { return original.isReady(); }
                @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
            };
            return stream;
        }
        @Override public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
