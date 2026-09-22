package com.gying.movie.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only explicitly trusted proxy peers may supply X-Forwarded-For. Never trusts CF headers directly. */
@Component
public class ClientIpResolver {
    public static final String ATTRIBUTE = ClientIpResolver.class.getName() + ".address";
    private final List<Network> trusted;

    public ClientIpResolver(@Value("${app.security.trusted-proxies:}") String cidrs) {
        trusted = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Network::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (!isTrusted(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 1024) return peer;
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 16) return peer;
        String current = peer;
        for (int i = chain.length - 1; i >= 0 && isTrusted(current); i--) {
            String next = normalize(chain[i].trim());
            if (next == null) return peer;
            current = next;
        }
        return current;
    }

    /** Downstream logging/authentication use the filter's validated address, not arbitrary headers. */
    public static String resolved(HttpServletRequest request) {
        Object resolved = request.getAttribute(ATTRIBUTE);
        return resolved instanceof String value ? value : request.getRemoteAddr();
    }

    private boolean isTrusted(String address) {
        byte[] bytes = parseAddress(address);
        return bytes != null && trusted.stream().anyMatch(n -> n.contains(bytes));
    }
    private static String normalize(String address) {
        byte[] bytes = parseAddress(address);
        try { return bytes == null ? null : InetAddress.getByAddress(bytes).getHostAddress(); }
        catch (Exception ignored) { return null; }
    }
    private static byte[] parseAddress(String address) {
        if (address == null || address.isBlank() || !address.matches("[0-9A-Fa-f:.]+")) return null;
        // Prevent shorthand IPv4 forms and hostname resolution.
        if (!address.contains(":")) {
            String[] octets = address.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) return null;
            }
        }
        try { return InetAddress.getByName(address).getAddress(); }
        catch (Exception ignored) { return null; }
    }
    private record Network(byte[] address, int bits) {
        static Network parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            byte[] address = parseAddress(parts[0]);
            if (address == null || parts.length > 2) throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            int bits = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
            if (bits <= 0 || bits > address.length * 8) throw new IllegalArgumentException("Invalid trusted proxy prefix");
            return new Network(address, bits);
        }
        boolean contains(byte[] candidate) {
            if (address.length != candidate.length) return false;
            for (int bit = 0; bit < bits; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (candidate[bit / 8] & mask)) return false;
            }
            return true;
        }
    }
}
