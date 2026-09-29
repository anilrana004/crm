package com.securetravels.crm.communications.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * The Customer 360 consent panel: current MARKETING status per channel plus the
 * append-only record that last changed it.
 */
@Schema(description = "Current MARKETING consent status per channel for a customer")
public record ConsentResponse(
        UUID recordId,
        UUID customerId,
        Map<String, String> marketingStatus
) {
}