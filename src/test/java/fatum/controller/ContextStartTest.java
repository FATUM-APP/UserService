package fatum.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The whole context starts with the controllers in place.
 *
 * <p>The three controllers are registered by the same {@code RequestMappingHandlerMapping}, and that
 * is where two handlers sharing a path and a verb blow up. A slice test with mocks never reaches
 * it, and neither does the build: the failure only appears when the application is started. This
 * test is the cheapest way to catch it before a deployment does.</p>
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:ctxstart;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "fatum.cognito.enabled=false",
        "app.lambda.secret=test-secret",
        "USER_POOL_ID=us-east-1_test",
        "server.port=0"
})
class ContextStartTest {

    /** The decoder of the resource server would reach Cognito at startup; it is stubbed here. */
    @TestConfiguration
    static class JwtStub {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("stub decoder");
            };
        }
    }

    @Test
    void theContextStarts() {
        // Levantar el contexto es la aserción: si dos mapeos se pisan, el contexto no arranca.
    }
}
