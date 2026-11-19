package com.egov.idgenerator.service;

import com.egov.idgenerator.entity.City;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Minimal CSV reader for cities.csv (header: tenant_id,city_code,city_name). No quoted fields needed. */
public final class CityCsvParser {

    private CityCsvParser() {
    }

    public static List<City> parse(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read cities file: " + path, e);
        }
        List<City> cities = new ArrayList<>();
        Set<String> tenants = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) { // line 0 is the header
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length != 3 || f[0].isBlank() || f[1].isBlank() || f[2].isBlank()) {
                throw new IllegalStateException("Bad cities.csv line " + (i + 1) + ": " + line);
            }
            City c = new City(f[0].trim(), f[1].trim(), f[2].trim());
            if (!tenants.add(c.tenantId()) || !codes.add(c.cityCode().toUpperCase())) {
                throw new IllegalStateException("Duplicate tenant_id or city_code in cities.csv line " + (i + 1));
            }
            cities.add(c);
        }
        return cities;
    }
}
