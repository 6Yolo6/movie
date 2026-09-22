package com.gying.movie.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
class ClientIpResolverTest {
    @Test void ignoresAllForwardedHeadersByDefault() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("203.0.113.8");
        r.addHeader("CF-Connecting-IP", "127.0.0.1"); r.addHeader("X-Forwarded-For", "10.0.0.1");
        assertEquals("203.0.113.8", new ClientIpResolver("").resolve(r));
    }
    @Test void acceptsOnlyTrustedRightHandHops() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("172.18.0.5");
        r.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.8");
        assertEquals("203.0.113.8", new ClientIpResolver("172.18.0.0/16").resolve(r));
    }
    @Test void invalidForwardedAddressFallsBackToPeer() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("127.0.0.1");
        r.addHeader("X-Forwarded-For", "example.com");
        assertEquals("127.0.0.1", new ClientIpResolver("127.0.0.1/32").resolve(r));
    }
    @Test void ipv6IsSupportedWithoutDnsResolution() {
        var r = new MockHttpServletRequest(); r.setRemoteAddr("::1");
        r.addHeader("X-Forwarded-For", "2001:db8::8");
        assertEquals("2001:db8:0:0:0:0:0:8", new ClientIpResolver("::1/128").resolve(r));
    }
    @Test void refusesTrustAllAndInvalidNetworks() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("example.com"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("192.168.1.1/99"));
    }
}
