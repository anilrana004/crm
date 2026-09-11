package com.securetravels.crm.common.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * OWASP HTML Sanitizer wrapper. All free-text fields accepted from clients
 * must pass through {@link #text(String)} before persisting (stored-XSS guard).
 */
public final class XssSanitizer {

    private static final PolicyFactory TEXT_POLICY =
            new HtmlPolicyBuilder().toFactory();

    private XssSanitizer() {}

    /**
     * Removes every tag and attribute, keeping only visible plain text.
     * The sanitizer HTML-escapes its output; we decode the five standard
     * entities back so the stored value is clean TEXT. Clients render it as
     * text (React escapes on render), so nothing double-escapes.
     */
    public static String text(String value) {
        if (value == null || value.isBlank()) return value;
        return unescape(TEXT_POLICY.sanitize(value)).trim();
    }

    private static String unescape(String html) {
        return html
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
    }
}