package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.CityResponse;
import com.egov.idgenerator.service.CityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    private final CityService cities;

    public CityController(CityService cities) {
        this.cities = cities;
    }

    @GetMapping
    public List<CityResponse> list() {
        return cities.findAllActive().stream()
                .map(c -> new CityResponse(c.tenantId(), c.cityCode(), c.cityName()))
                .toList();
    }
}
