package com.gying.movie.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class ApiSecurityFilterTest {
    private ApiSecurityFilter filter(RedisRateLimiter limiter) {
        return new ApiSecurityFilter(limiter, new ClientIpResolver(""), new ObjectMapper(), 120, 1200);
    }
    @Test void oversizedPageIsRejectedBeforeRedisOrDatabase() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        var req=new MockHttpServletRequest("GET", "/api/movies"); req.setParameter("size", "100000");
        var res=new MockHttpServletResponse(); filter(limiter).doFilter(req,res,chain);
        assertEquals(400,res.getStatus()); verifyNoInteractions(limiter,chain);
    }
    @Test void publicLimitAboveHardCapIsRejected() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        var req=new MockHttpServletRequest("GET", "/api/movies"); req.setParameter("limit", "101");
        var res=new MockHttpServletResponse(); filter(limiter).doFilter(req,res,chain);
        assertEquals(400,res.getStatus()); verifyNoInteractions(limiter,chain);
    }
    @Test void adminLimitAbovePublicCapIsAcceptedWithinAdminHardCap() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        var req=new MockHttpServletRequest("POST", "/api/admin/resource-hub/discoveries/reconcile");
        req.setParameter("limit", "2000");
        var res=new MockHttpServletResponse(); filter(limiter).doFilter(req,res,chain);
        assertEquals(200,res.getStatus()); verify(chain).doFilter(any(), any());
    }
    @Test void adminLimitAboveAdminHardCapIsRejected() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        var req=new MockHttpServletRequest("POST", "/api/admin/resource-hub/discoveries/reconcile");
        req.setParameter("limit", "5001");
        var res=new MockHttpServletResponse(); filter(limiter).doFilter(req,res,chain);
        assertEquals(400,res.getStatus()); verifyNoInteractions(limiter,chain);
    }
    @Test void rateRejectionHasRetryAfterAndNoDownstreamWork() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        doThrow(new RedisRateLimiter.RateLimitExceededException(1001)).when(limiter).require(anyString(),anyString(),anyInt(),any());
        var req=new MockHttpServletRequest("POST", "/api/auth/login"); var res=new MockHttpServletResponse();
        filter(limiter).doFilter(req,res,chain);
        assertEquals(429,res.getStatus()); assertEquals("2",res.getHeader("Retry-After")); verifyNoInteractions(chain);
    }
    @Test void redisOutageIs503NotUnrestrictedTraffic() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Security service temporarily unavailable"))
                .when(limiter).require(anyString(),anyString(),anyInt(),any());
        var res=new MockHttpServletResponse(); filter(limiter).doFilter(new MockHttpServletRequest("GET","/api/movies"),res,chain);
        assertEquals(503,res.getStatus()); verifyNoInteractions(chain);
    }
    @Test void encodedApiPrefixCannotBypassTheFilter() throws Exception {
        var limiter=mock(RedisRateLimiter.class); var chain=mock(FilterChain.class);
        doThrow(new RedisRateLimiter.RateLimitExceededException(1000)).when(limiter).require(anyString(),anyString(),anyInt(),any());
        var res=new MockHttpServletResponse();
        filter(limiter).doFilter(new MockHttpServletRequest("POST","/%61pi/auth/login"),res,chain);
        assertEquals(429,res.getStatus()); verifyNoInteractions(chain);
    }
    @Test void chunkedBodiesAreBoundedDuringReading() throws Exception {
        var req=new MockHttpServletRequest("POST","/api/resources"); req.setContentType("application/json");
        req.setContent(new byte[1024*1024+1]);
        var unknownLength=new HttpServletRequestWrapper(req) { @Override public long getContentLengthLong(){return -1;} };
        var res=new MockHttpServletResponse();
        filter(mock(RedisRateLimiter.class)).doFilter(unknownLength,res,(r,s)->r.getInputStream().readAllBytes());
        assertEquals(413,res.getStatus());
    }
    @Test void forwardedSpoofDoesNotChangeResolvedIdentity() throws Exception {
        var req=new MockHttpServletRequest("GET","/api/movies"); req.setRemoteAddr("203.0.113.9");
        req.addHeader("X-Forwarded-For","1.1.1.1");
        filter(mock(RedisRateLimiter.class)).doFilter(req,new MockHttpServletResponse(),(r,s)->
                assertEquals("203.0.113.9",r.getAttribute(ClientIpResolver.ATTRIBUTE)));
    }
}
