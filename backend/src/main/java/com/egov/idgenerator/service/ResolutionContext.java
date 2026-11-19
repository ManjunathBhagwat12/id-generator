package com.egov.idgenerator.service;

import com.egov.idgenerator.entity.City;
import java.time.LocalDate;

/**
 * Per-request values shared by every ID of the request. {@code businessDate} is captured ONCE so all IDs
 * of a bulk request agree on the financial year even if midnight on 31 March passes mid-request.
 */
public record ResolutionContext(City city, LocalDate businessDate) {
}
