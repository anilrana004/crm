package com.securetravels.crm.communications.dto;

import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.communications.consent.ConsentStatus;
import com.securetravels.crm.communications.consent.Purpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Record a GRANTED/REVOKED consent instruction (Phase 5 Module 1). Only staff
 * recording an explicitly expressed preference use this endpoint; inbound
 * opt-out keywords and unsubscribe links write their own rows.
 */
@Schema(description = "Record a customer's explicit consent instruction")
public record ConsentRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID customerId,

        @Schema(allowableValues = {"WHATSAPP", "EMAIL", "SMS"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull TimelineEvent.Channel channel,

        @Schema(description = "Defaults to MARKETING", allowableValues = {"MARKETING", "TRANSACTIONAL"})
        Purpose purpose,

        @Schema(description = "Only GRANTED or REVOKED may be recorded", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull ConsentStatus status,

        @Schema(description = "Free-text evidence, e.g. 'captured on package enquiry form'")
        String evidence) {
}