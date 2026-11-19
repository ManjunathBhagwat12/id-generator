package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment.Placeholder;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * [CY:pattern] - the business date (Asia/Kolkata) rendered with a Java date pattern, e.g. yyyy -> 2026,
 * MM-yyyy -> 11-2026. Only numeric date fields are allowed (year, month, day) so the output is locale-independent
 * and contains only digits and the separators - / . _ (time fields or month names are rejected).
 */
@Component
public class CyPlaceholderResolver implements PlaceholderResolver {

    /** A maximal run of ONE pattern letter (greedy), optionally followed by one separator. */
    private static final Pattern RUN = Pattern.compile("(y+|M+|d+)([-/._]?)");

    @Override
    public String kind() {
        return "CY";
    }

    @Override
    public String problem(Placeholder p) {
        if (p.arg() == null || !isSafe(p.arg())) {
            return "[CY" + (p.arg() == null ? "" : ":" + p.arg()) + "] has an invalid date pattern; use year/month/day "
                    + "tokens yyyy, yy, MM, M, dd, d separated by - / . _ (e.g. [CY:yyyy], [CY:MM-yyyy])";
        }
        return null;
    }

    /**
     * Each maximal run of the same letter must be a numeric field: yyyy or yy, M or MM, d or dd. Checking whole runs
     * matters: "MMMM" is ONE token (the month name, e.g. "November"), not "MM" twice. No trailing separator.
     */
    static boolean isSafe(String pattern) {
        if (pattern.isEmpty()) {
            return false;
        }
        Matcher m = RUN.matcher(pattern);
        int pos = 0;
        String lastSeparator = "";
        while (pos < pattern.length()) {
            if (!m.find(pos) || m.start() != pos) {
                return false;
            }
            String run = m.group(1);
            boolean ok = run.charAt(0) == 'y' ? run.length() == 2 || run.length() == 4 : run.length() <= 2;
            if (!ok) {
                return false;
            }
            lastSeparator = m.group(2);
            pos = m.end();
        }
        return lastSeparator.isEmpty();
    }

    @Override
    public String resolve(Placeholder p, ResolutionContext ctx) {
        return DateTimeFormatter.ofPattern(p.arg(), Locale.ROOT).format(ctx.businessDate());
    }
}
