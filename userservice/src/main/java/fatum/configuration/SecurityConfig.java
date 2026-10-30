package fatum.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/swagger-ui/**",
                                // The path springdoc is configured with in application.yaml.
                                "/api-docs/**",
                                "/users/validate-signup",
                                "/users/register"
                        ).permitAll()



                        // The panel of the platform is behind the administrator group.
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/users/upgrade/**").hasRole("VERIFIED")
                        // Endpoint(s) que el cliente M2M puede llamar
                        .requestMatchers(HttpMethod.GET, "/users/by-id/**").hasAuthority("SCOPE_api.catalog/read")
                        .anyRequest().authenticated()

                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter groupsConverter = new JwtGrantedAuthoritiesConverter();
        // The groups of the pool travel in this claim; Spring reads the authorities from there.
        groupsConverter.setAuthoritiesClaimName("cognito:groups");
        // The ROLE_ prefix is what hasRole() expects.
        groupsConverter.setAuthorityPrefix("ROLE_");

        // Convierte scope -> SCOPE_* (para clientes M2M)
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        scopeConverter.setAuthoritiesClaimName("scope");
        scopeConverter.setAuthorityPrefix("SCOPE_");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new java.util.HashSet<org.springframework.security.core.GrantedAuthority>();
            authorities.addAll(groupsConverter.convert(jwt));
            authorities.addAll(scopeConverter.convert(jwt));
            return authorities;
        });
        return jwtAuthenticationConverter;
    }
}
