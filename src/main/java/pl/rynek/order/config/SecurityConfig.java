package pl.rynek.order.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import pl.rynek.order.common.ProblemJson;

/**
 * Zamówienia mają tylko endpointy {@code user} i {@code seller}, więc serwis sam sprawdza JWT z identity-service (klucze z JWKS), także przy
 * {@code JWT_ENFORCE=false} w gateway. {@code /internal/**} (payment-service) jest otwarte, bo gateway go nie wystawia.
 */
@Configuration
public class SecurityConfig {

    private static final RequestMatcher INTERNAL = PathPatternRequestMatcher.withDefaults().matcher("/internal/**");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Bezstanowe API z tokenem Bearer: bez sesji i ciasteczek, więc bez CSRF.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(INTERNAL).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(ignoreTokenOnInternal())
                        .jwt(jwt -> {
                        })
                        .authenticationEntryPoint(unauthorized()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler((request, response, ex) ->
                                ProblemJson.write(request, response, HttpStatus.FORBIDDEN, "Nie masz uprawnień do tej operacji.")))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${rynek.jwt.jwk-set-uri}") String jwkSetUri, @Value("${rynek.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    private static BearerTokenResolver ignoreTokenOnInternal() {
        DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
        return request -> INTERNAL.matches(request) ? null : bearer.resolve(request);
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            ProblemJson.write(request, response, HttpStatus.UNAUTHORIZED, "Zaloguj się, aby kontynuować.");
        };
    }
}
