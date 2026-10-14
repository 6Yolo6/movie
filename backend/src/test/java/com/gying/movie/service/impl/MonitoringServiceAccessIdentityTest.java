package com.gying.movie.service.impl;

import com.gying.movie.security.ClientIpResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MonitoringServiceAccessIdentityTest {
    static class CapturingJdbc extends JdbcTemplate {
        String sql;
        List<Object> args;
        boolean fail;
        @Override public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = Arrays.asList(args);
            if (fail) throw new IllegalStateException("fixture-private-database-detail");
            return 1;
        }
    }
    final CapturingJdbc jdbc = new CapturingJdbc();
    final MonitoringService service = new MonitoringService(jdbc, mock(StringRedisTemplate.class));
    final MockHttpServletRequest request = new MockHttpServletRequest();

    void record() { service.access("/api/movies/list?credential=fixture-query", "GET", 200, 7, request, "API"); }

    @Test void rawIdentityIsAddedWithoutPersistingOtherHeadersOrQueries() {
        request.setRemoteAddr("192.0.2.7");
        String agent = "Fixture/1.0 (quote=\"; slash=\\; 中文)";
        request.addHeader("User-Agent", agent);
        request.addHeader("Referer", "https://fixture.invalid/movie/example?session=fixture-private#fragment");
        request.addHeader("Authorization", "Bearer fixture-private-authorization");
        request.addHeader("Cookie", "session=fixture-private-cookie");
        record();
        assertTrue(jdbc.sql.contains("client_ip,user_agent"));
        assertEquals(10, jdbc.args.size());
        assertEquals("/api/movies/list", jdbc.args.get(0));
        assertEquals("/movie/example", jdbc.args.get(5));
        assertEquals("192.0.2.7", jdbc.args.get(8));
        assertEquals(agent, jdbc.args.get(9));
        assertEquals(RegistrationService.sha256("192.0.2.7|" + agent), jdbc.args.get(3));
        assertEquals(RegistrationService.sha256(agent), jdbc.args.get(4));
        assertFalse(jdbc.args.toString().contains("fixture-private"));
        assertFalse(jdbc.args.toString().contains("fixture-query"));
    }

    @Test void untrustedForwardedHeadersCannotForgeStoredIp() {
        request.setRemoteAddr("192.0.2.8");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        request.addHeader("CF-Connecting-IP", "203.0.113.98");
        request.addHeader("X-Real-IP", "203.0.113.97");
        request.setAttribute(ClientIpResolver.ATTRIBUTE, new ClientIpResolver("").resolve(request));
        record();
        assertEquals("192.0.2.8", jdbc.args.get(8));
    }

    @Test void onlyExplicitProxyPeersCanSupplyTheStoredAddress() {
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.99, 192.0.2.9");
        request.addHeader("CF-Connecting-IP", "203.0.113.98");
        request.setAttribute(ClientIpResolver.ATTRIBUTE, new ClientIpResolver("198.51.100.10/32").resolve(request));
        record();
        assertEquals("192.0.2.9", jdbc.args.get(8));
    }

    @Test void normalizedIpv6SurvivesTheTrustedProxyChain() {
        request.setRemoteAddr("::1");
        request.addHeader("X-Forwarded-For", "2001:db8::8");
        request.setAttribute(ClientIpResolver.ATTRIBUTE, new ClientIpResolver("::1/128").resolve(request));
        record();
        assertEquals("2001:db8:0:0:0:0:0:8", jdbc.args.get(8));
    }

    @Test void missingAgentRemainsNullRatherThanAnInventedFingerprint() {
        record();
        assertNull(jdbc.args.get(9));
        assertEquals(RegistrationService.sha256(""), jdbc.args.get(4));
    }

    @Test void oversizedAgentIsBoundedByCodePointsWithoutSplittingUnicode() {
        String prefix = "a".repeat(1023) + "😀";
        String full = prefix + "suffix-not-stored";
        request.addHeader("User-Agent", full);
        record();
        assertEquals(prefix, jdbc.args.get(9));
        String stored = (String) jdbc.args.get(9);
        assertEquals(1024, stored.codePointCount(0, stored.length()));
        assertEquals(RegistrationService.sha256(full), jdbc.args.get(4));
    }

    @Test void pageViewsKeepTheSameIdentityAndApiAccountingBoundary() {
        request.setRemoteAddr("192.0.2.11");
        request.addHeader("User-Agent", "Fixture/1.0");
        service.access("/movie/example", "VIEW", 200, 0, request, "PAGE_VIEW");
        assertEquals("PAGE_VIEW", jdbc.args.get(7));
        assertEquals("192.0.2.11", jdbc.args.get(8));
        assertEquals("Fixture/1.0", jdbc.args.get(9));
    }

    @Test void telemetryFailureDoesNotTurnTheApiResponseIntoAnError() {
        jdbc.fail = true;
        assertDoesNotThrow(this::record);
    }
}
