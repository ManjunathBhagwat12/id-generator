package com.egov.idgenerator.parser;

/**
 * One piece of a parsed template format: either literal text or a {@code [KIND:arg]} placeholder.
 */
public sealed interface Segment {

    record Literal(String text) implements Segment {
    }

    /**
     * @param name         full name as written, e.g. "SEQ", "SEQ_WS", "CITY", "FY"
     * @param kind         placeholder family: "SEQ" for any SEQ / SEQ_xxx, otherwise same as name
     * @param namedCounter counter name for SEQ_xxx placeholders (the full name), null for plain SEQ / non-SEQ
     * @param arg          text after ':' (e.g. "yyyy-yy", "6") or null
     */
    record Placeholder(String name, String kind, String namedCounter, String arg) implements Segment {
        public boolean isSequence() {
            return "SEQ".equals(kind);
        }
    }
}
