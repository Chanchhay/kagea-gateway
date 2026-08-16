package co.istad.gatewaybff;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;

/**
 * The BFF's security. The browser only ever holds an opaque session cookie —
 * access tokens stay on this server and are attached to backend calls by the
 * {@code TokenRelay} filter configured in {@code application.yaml}.
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Page routes needing a signed-in user. Mirrors the old Next.js proxy matcher. */
    private static final String[] PROTECTED_PAGES = {
            "/profile",
            "/job-seeker/**",
            "/recruiter/**",
    };

    /** Backend paths needing a signed-in user; the rest of /api stays public. */
    private static final String[] PROTECTED_API = {
            "/api/v1/me",
            "/api/v1/job-seeker/**",
            "/api/v1/recruiter/**",
            "/api/v1/moderator/**",
            "/api/v1/files/**",
    };

    private final ReactiveClientRegistrationRepository clientRegistrationRepository;

    /**
     * Algorithm Keycloak signs ID tokens with. The {@code ai-career} realm is set
     * to RS512, while Spring's default OIDC decoder only accepts RS256 — leaving
     * this unset fails the login with {@code [invalid_id_token] Unsupported
     * algorithm of RS512}. The backend resource server already accepts
     * RS256/384/512, so this keeps the two in step.
     */
    @Value("${app.oidc.id-token-signature-algorithm:RS512}")
    private String idTokenSignatureAlgorithm;

    @Bean
    public ReactiveJwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        ReactiveOidcIdTokenDecoderFactory factory = new ReactiveOidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(registration ->
                SignatureAlgorithm.from(idTokenSignatureAlgorithm));
        return factory;
    }

    @Bean
    public SecurityWebFilterChain bffFilterChain(ServerHttpSecurity http) {

        http.authorizeExchange(exchanges -> exchanges
                .pathMatchers(PROTECTED_API).authenticated()
                .pathMatchers(PROTECTED_PAGES).authenticated()
                // Everything else — landing page, /jobs, /register, static assets,
                // /api/v1/public/** — stays reachable while signed out.
                .anyExchange().permitAll()
        );

        http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
        http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);

        // Disabled to match the reference setup. Tolerable only because the
        // session cookie is SameSite=Lax and the gateway is the single origin;
        // revisit if the frontend is ever served from another host.
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);

        http.oauth2Login(oauth2 -> oauth2
                .authenticationSuccessHandler(authenticationSuccessHandler()));

        http.exceptionHandling(exceptions ->
                exceptions.authenticationEntryPoint(authenticationEntryPoint()));

        http.logout(logout -> logout
                .logoutSuccessHandler(oidcLogoutSuccessHandler()));

        return http.build();
    }

    /**
     * A browser navigation to a protected page should bounce to Keycloak, but an
     * XHR from RTK Query must get a plain 401 — following a 302 to the login page
     * hands the caller an HTML document where it expects JSON.
     */
    private ServerAuthenticationEntryPoint authenticationEntryPoint() {
        ServerAuthenticationEntryPoint browser =
                new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak");
        ServerAuthenticationEntryPoint api =
                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);

        return (ServerWebExchange exchange, AuthenticationException ex) ->
                ServerWebExchangeMatchers.pathMatchers("/api/**")
                        .matches(exchange)
                        .flatMap(match -> match.isMatch()
                                ? api.commence(exchange, ex)
                                : browser.commence(exchange, ex));
    }

    /**
     * After sign-in, return the user to the page that bounced them. When there
     * is no saved request — a plain "Login" click — fall through to
     * {@code /auth/continue}, the app's page that routes by role.
     */
    private ServerAuthenticationSuccessHandler authenticationSuccessHandler() {
        RedirectServerAuthenticationSuccessHandler handler =
                new RedirectServerAuthenticationSuccessHandler("/auth/continue");
        handler.setRequestCache(new WebSessionServerRequestCache());
        return handler;
    }

    /**
     * RP-initiated logout: clears the gateway session, then hands off to Keycloak
     * so the IdP session ends too. Without it, signing out would be silently
     * undone by the next authorization request.
     */
    private ServerLogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedServerLogoutSuccessHandler handler =
                new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
