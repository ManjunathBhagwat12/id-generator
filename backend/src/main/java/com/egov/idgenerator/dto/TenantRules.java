package com.egov.idgenerator.dto;

import java.util.Locale;

/** Shared tenant field rules and normalisation. */
public final class TenantRules {

    public static final String TENANT_ID = "^[a-z0-9]+(\\.[a-z0-9_-]+)+$";
    public static final String TENANT_ID_MSG = "must be lowercase letters/digits/_/- with at least one dot, e.g. tn.coimbatore";
    public static final String CITY_CODE = "^[A-Z0-9]{2,10}$";
    public static final String CITY_CODE_MSG = "must be 2 to 10 letters or digits, e.g. CBE";
    public static final String CITY_NAME = "^[\\p{L}\\p{M}0-9][\\p{L}\\p{M}0-9 .,'()&/-]*$";
    public static final String CITY_NAME_MSG = "contains unsupported characters";

    private TenantRules() {
    }

    public static String tenantId(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    public static String cityCode(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    public static String cityName(String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", " ");
    }
}
