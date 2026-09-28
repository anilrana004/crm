package com.securetravels.crm.communications.dto;

import com.securetravels.crm.communications.SubjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Operator-initiated send (Module 4).
 *
 * <p>Deliberately free-form {@code bodyValues} rather than a fixed request
 * record per template: nine templates x four parameters would be nine
 * near-identical records, and the arity is already enforced once, centrally,
 * against {@code whatsapp_templates.expected_params}. A wrong count fails with
 * a 400 that names the expectation.
 */
@Schema(description = "Queue a WhatsApp template to a customer")
public record WhatsAppSendRequest(

        @Schema(description = "Lead, Customer360, or Booking this message belongs to", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull SubjectType subjectType,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull UUID subjectId,

        @Schema(example = "BOOKING_CONFIRMED", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String templateCode,

        @Schema(example = "9876500000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String mobile,

        @Schema(description = "Positional {{1}}..{{4}} values; count must match the template's expected_params",
                example = "[\"Asha\", \"TOH-2026-0001\", \"Manali\", \"2026-11-02\"]")
        List<@NotBlank String> bodyValues) {
}
