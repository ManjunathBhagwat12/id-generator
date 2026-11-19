package com.egov.idgenerator.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication for a request carrying a valid API key. Role API_CLIENT only: never ADMIN. */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE = "ROLE_API_CLIENT";

    private final ApiClient client;

    public ApiKeyAuthentication(ApiClient client) {
        super(List.of(new SimpleGrantedAuthority(ROLE)));
        this.client = client;
        setAuthenticated(true);
    }

    public ApiClient client() {
        return client;
    }

    @Override
    public Object getCredentials() {
        return null; // the key is not kept after authentication
    }

    @Override
    public Object getPrincipal() {
        return client;
    }

    @Override
    public String getName() {
        return "api:" + client.credentialId();
    }
}
