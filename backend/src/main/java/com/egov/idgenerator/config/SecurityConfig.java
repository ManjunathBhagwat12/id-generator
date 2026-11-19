package com.egov.idgenerator.config;

import com.egov.idgenerator.dto.ErrorResponse;
import com.egov.idgenerator.repository.AdminUserRepository;
import com.egov.idgenerator.security.ApiKeyAuthenticationFilter;
import com.egov.idgenerator.service.ApiCredentialService;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Session-cookie authentication for the admin module.
 * <ul>
 *   <li>Login: POST /api/v1/auth/login (form fields username, password) -> HttpOnly session cookie.</li>
 *   <li>Logout: POST /api/v1/auth/logout -> session invalidated.</li>
 *   <li>/api/v1/admin/** requires ROLE_ADMIN (401 when anonymous, 403 when authenticated without the role).</li>
 *   <li>The public ID generator API (issue, bulk, recent, templates, cities) stays open and CSRF-exempt: it uses no session.</li>
 *   <li>X-API-Key: external applications, ROLE_API_CLIENT, stateless; may call issuance, never /api/v1/admin/**.
 *       With idgen.security.require-api-key=true, issuance needs a key or an admin session.</li>
 *   <li>CSRF: double-submit cookie (XSRF-TOKEN cookie, X-XSRF-TOKEN header) on every other state-changing request,
 *       plus SameSite=Lax session cookie.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AdminUserRepository admins) {
        return username -> admins.findByUsername(username)
                .map(a -> User.withUsername(a.username()).password(a.passwordHash()).roles("ADMIN")
                        .disabled(!a.enabled()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user")); // message is never shown to clients
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper, ApiCredentialService apiCredentials,
                                    @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies,
                                    @Value("${idgen.security.require-api-key:false}") boolean requireApiKey)
            throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse(); // SPA must read it
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Lax").secure(secureCookies));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // load the token eagerly so the cookie is always issued

        http
            .csrf(c -> c.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler)
                    .ignoringRequestMatchers("/api/v1/ids/**"))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/ids/issue", "/api/v1/ids/bulk").access(
                            requireApiKey ? AuthorityAuthorizationManager.hasAnyRole("API_CLIENT", "ADMIN")
                                          : (auth, ctx) -> new AuthorizationDecision(true))
                    .requestMatchers(HttpMethod.GET, "/api/v1/ids/recent", "/api/v1/templates", "/api/v1/cities").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .formLogin(f -> f
                    .loginPage("/api/v1/auth/login")          // no generated HTML login page
                    .loginProcessingUrl("/api/v1/auth/login")
                    .successHandler((req, res, auth) -> json(mapper, res, 200,
                            Map.of("authenticated", true, "username", auth.getName())))
                    .failureHandler((req, res, ex) -> error(mapper, req, res, HttpStatus.UNAUTHORIZED,
                            "Invalid username or password"))
                    .permitAll())
            .logout(l -> l
                    .logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> error(mapper, req, res, HttpStatus.UNAUTHORIZED,
                            "Authentication required"))
                    .accessDeniedHandler((req, res, ex) -> error(mapper, req, res, HttpStatus.FORBIDDEN,
                            ex instanceof CsrfException ? "Missing or invalid CSRF token" : "Access denied")))
            // X-API-Key for external applications: stateless, before anonymous access is assigned.
            .addFilterBefore(new ApiKeyAuthenticationFilter(apiCredentials, mapper), AnonymousAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .headers(Customizer.withDefaults());
        return http.build();
    }

    private static void error(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res,
                              HttpStatus status, String message) throws IOException {
        json(mapper, res, status.value(), new ErrorResponse(Instant.now().toString(), status.value(),
                status.getReasonPhrase(), message, req.getRequestURI()));
    }

    private static void json(ObjectMapper mapper, HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), body);
    }
}
