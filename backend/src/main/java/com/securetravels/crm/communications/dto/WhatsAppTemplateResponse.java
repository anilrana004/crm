package com.securetravels.crm.communications.dto;

import com.securetravels.crm.communications.WhatsAppTemplate;

public record WhatsAppTemplateResponse(
        String code,
        String interaktName,
        String label,
        String languageCode,
        int expectedParams,
        boolean enabled) {

    public static WhatsAppTemplateResponse from(WhatsAppTemplate t) {
        return new WhatsAppTemplateResponse(t.getCode(), t.getInteraktName(), t.getLabel(),
                t.getLanguageCode(), t.getExpectedParams(), t.isEnabled());
    }
}
