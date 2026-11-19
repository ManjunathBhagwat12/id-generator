package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.AnalyticsResponse;
import com.egov.idgenerator.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (SecurityConfig: /api/v1/admin/** requires ROLE_ADMIN). Read-only GET, so no CSRF token is involved. */
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {

    private final AnalyticsService service;

    public AdminAnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    /** @param period 7d (default) or 30d; anything else is 400 */
    @GetMapping
    public AnalyticsResponse analytics(@RequestParam(defaultValue = "7d") String period) {
        return service.analytics(period);
    }
}
