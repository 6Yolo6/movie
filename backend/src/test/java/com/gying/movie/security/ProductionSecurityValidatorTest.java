package com.gying.movie.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class ProductionSecurityValidatorTest {
    private MockEnvironment valid() {
        var env=new MockEnvironment().withProperty("spring.datasource.username","gying_app")
                .withProperty("app.cors.allowed-origin","https://example.invalid");
        for(String key:new String[]{"spring.datasource.password","spring.data.redis.password","minio.access-key",
                "minio.secret-key","jwt.secret","app.internal-token","gying-source.token","social-publisher.token"})
            env.withProperty(key,"unit-test-random-looking-material-0000000001");
        return env;
    }
    @Test void secureConfigurationPasses() { assertDoesNotThrow(()->new ProductionSecurityValidator(valid()).validate()); }
    @Test void rootAndMissingTokensAreRejectedWithoutPrintingValues() {
        var env=valid().withProperty("spring.datasource.username","root");
        assertThrows(IllegalStateException.class,()->new ProductionSecurityValidator(env).validate());
        var missing=valid().withProperty("gying-source.token","");
        assertTrue(assertThrows(IllegalStateException.class,()->new ProductionSecurityValidator(missing).validate())
                .getMessage().contains("gying-source.token"));
    }
    @Test void productionCannotDisableBotRateLimit() {
        var env=valid().withProperty("qq-bot.rate-limit-per-minute","0");
        assertThrows(IllegalStateException.class,()->new ProductionSecurityValidator(env).validate());
    }
}
