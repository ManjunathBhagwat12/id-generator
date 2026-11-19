package com.egov.idgenerator.security;

import com.egov.idgenerator.dto.ErrorResponse;
import com.egov.idgenerator.service.ApiCredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry an X-API-Key header.
 * <ul>
 *   <li>No header: does nothing; the request continues as before (session, anonymous public access).</li>
 *   <li>Header present but invalid or revoked: 401 immediately. It never falls back to anonymous access.</li>
 *   <li>Valid: the request is authenticated as API_CLIENT for this request only (no session is created or changed).</li>
 * </ul>
 * Not a Spring bean on purpose: it is added only to the Spring Security chain, not to the servlet filter chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final ApiCredentialService credentials;
    private final ObjectMapper mapper;

    public ApiKeyAuthenticationFilter(ApiCredentialService credentials, ObjectMapper mapper) {
        this.credentials = credentials;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        if (key == null) {
            chain.doFilter(req, res);
            return;
        }
        Optional<ApiClient> client = credentials.authenticate(key.trim());
        if (client.isEmpty()) {
            SecurityContextHolder.clearContext();
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
            res.setHeader("WWW-Authenticate", "ApiKey");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), new ErrorResponse(Instant.now().toString(), 401, "Unauthorized",
                    "Invalid or revoked API key", req.getRequestURI()));
            return;
        }
        // Replaces any session authentication for this request: an API key never acts with admin rights.
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new ApiKeyAuthentication(client.get()));
        SecurityContextHolder.setContext(ctx);
        chain.doFilter(req, res);
    }
}
