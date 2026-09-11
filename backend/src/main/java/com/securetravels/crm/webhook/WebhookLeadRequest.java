package com.securetravels.crm.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;

/**
 * Public website-form lead payload. Accepts camelCase and the legacy
 * snake_case names ({@code customer_name}, {@code mobile_number}, ...).
 * Auto-provisioned, so it is validated manually in WebhookService rather
 * than via bean-validation annotations.
 */
public record WebhookLeadRequest(
        @JsonAlias("customer_name") String customerName,
        @JsonAlias("mobile_number") String mobileNumber,
        @JsonAlias("whatsapp_number") String whatsappNumber,
        String email,
        @JsonAlias("lead_source") String source,
        String destination,
        @JsonAlias("num_persons") Integer numPersons,
        BigDecimal budget,
        @JsonAlias("message") String remarks,
        @JsonAlias("consent_given") boolean consentGiven,
        @JsonAlias("consent_scope") String consentScope
) {}