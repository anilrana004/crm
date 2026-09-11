package com.securetravels.crm.common.util;

/**
 * Normalizes phone numbers for Indian-mobile deduplication.
 * Accepted forms: "+91-98765 43210", "9876543210", "091..." -> 9876543210.
 * Non-Indian / longer numbers keep their full digit string (dedup still works).
 */
public final class PhoneUtils {

    private PhoneUtils() {}

    /** Validates an optional Indian mobile number. */
    public static boolean isValidMobile(String value) {
        if (value == null || value.isBlank()) return false;
        return normalize(value) != null;
    }

    /**
     * Returns normalized 10-digit Indian mobile (without +91 prefix), or null if invalid.
     */
    public static String normalize(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 15) return null;
        if (digits.startsWith("91") && digits.length() == 12) digits = digits.substring(2);
        if (digits.startsWith("0") && digits.length() == 11) digits = digits.substring(1);
        if (digits.length() == 10 && digits.charAt(0) >= '6' && digits.charAt(0) <= '9') return digits;
        return null; // only Indian mobile numbers are accepted in Phase 1
    }
}