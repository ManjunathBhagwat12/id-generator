package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment.Placeholder;

/**
 * Extension point for placeholders whose value is the same for every ID of a request (CITY, FY, CY).
 * To support a new placeholder type, add one @Component implementing this interface.
 * SEQ is intentionally different (value differs per ID, needs the database) and is handled by SequenceService.
 */
public interface PlaceholderResolver {

    /** Placeholder kind handled, e.g. "CITY". */
    String kind();

    /** Why this placeholder's argument is unusable, or null if it is fine. */
    default String problem(Placeholder placeholder) {
        return null;
    }

    String resolve(Placeholder placeholder, ResolutionContext ctx);
}
