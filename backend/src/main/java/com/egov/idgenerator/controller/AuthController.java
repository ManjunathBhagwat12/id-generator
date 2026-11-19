package com.egov.idgenerator.controller;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login/logout themselves are handled by Spring Security filters (see SecurityConfig). This endpoint reports the
 * current session and, as a side effect, makes sure the browser holds a CSRF cookie before its first POST.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        boolean in = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return in ? Map.of("authenticated", true, "username", auth.getName())
                  : Map.of("authenticated", false);
    }
}
