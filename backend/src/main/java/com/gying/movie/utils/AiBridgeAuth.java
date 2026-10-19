package com.gying.movie.utils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
/** Dedicated credential grants only AI queue claim/delivery, never other internal APIs. */
@Component
public class AiBridgeAuth {
 private final String expected;
 public AiBridgeAuth(@Value("${APP_AI_BRIDGE_TOKEN:}") String expected) { this.expected=expected; }
 public void require(String supplied) {
  if(expected==null || expected.length()<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI bridge is not configured");
  if(supplied==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid AI bridge credential");
 }
}
