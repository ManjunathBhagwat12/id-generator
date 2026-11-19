package com.egov.idgenerator.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Browser-like test client: keeps cookies and echoes the XSRF-TOKEN cookie as the X-XSRF-TOKEN header. */
class AdminClient {

    record Resp(int status, String body, HttpHeaders headers) {
        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Not JSON: " + body, e);
            }
        }
    }

    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

    AdminClient(String base) {
        this.base = base;
    }

    private String xsrf() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            for (HttpCookie c : cookies.getCookieStore().getCookies()) {
                if (c.getName().equals("XSRF-TOKEN")) {
                    return c.getValue();
                }
            }
            get("/api/v1/auth/me"); // issues the CSRF cookie
        }
        throw new IllegalStateException("No XSRF-TOKEN cookie issued");
    }

    private Resp send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Resp get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    private Resp withBody(String method, String path, String contentType, String body, boolean csrf) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (csrf) {
                b.header("X-XSRF-TOKEN", xsrf());
            }
            return send(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Resp json(String method, String path, String json) {
        return withBody(method, path, "application/json", json, true);
    }

    /** Same, but deliberately without the CSRF header. */
    Resp jsonWithoutCsrf(String method, String path, String json) {
        return withBody(method, path, "application/json", json, false);
    }

    /** A request with extra headers (e.g. X-API-Key); body may be null for GET. No CSRF header is added. */
    Resp withHeader(String method, String path, String json, String header, String value) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header(header, value);
        if (json == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return send(b);
    }

    Resp login(String user, String pass) {
        String form = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8);
        return withBody("POST", "/api/v1/auth/login", "application/x-www-form-urlencoded", form, true);
    }

    Resp logout() {
        return withBody("POST", "/api/v1/auth/logout", "application/json", "{}", true);
    }
}
