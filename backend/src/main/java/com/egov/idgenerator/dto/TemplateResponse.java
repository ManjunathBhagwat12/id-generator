package com.egov.idgenerator.dto;

/** @param counterScope human-readable description of which counter the template draws from */
public record TemplateResponse(String id, String name, String format, boolean enabled, String counterScope) {
}
