package com.gying.movie.security;
import com.gying.movie.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExceptionSafetyTest {
    @Test void unexpectedErrorsDoNotEchoSqlOrSecretsAndAreNot400() {
        var response=new GlobalExceptionHandler().unexpected(new RuntimeException("password=fixture SQL SELECT"));
        assertEquals(500,response.getStatusCode().value());
        assertFalse(response.getBody().toString().contains("fixture"));
    }
    @Test void invalidArgumentsDoNotEchoUntrustedInput() {
        var response=new GlobalExceptionHandler().invalid(new IllegalArgumentException("private fixture"));
        assertEquals(400,response.getStatusCode().value()); assertFalse(response.getBody().toString().contains("fixture"));
    }
}
