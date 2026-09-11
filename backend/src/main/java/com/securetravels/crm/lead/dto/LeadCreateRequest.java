package com.securetravels.crm.lead.dto;

import com.securetravels.crm.lead.Lead;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lead creation payload. Never binds to the entity — always validated first
 * (security requirement 4: phone format is enforced server-side).
 */
public record LeadCreateRequest(
        @NotBlank(message = "customerName is required")
        @Size(max = 200, message = "customerName too long")
        String customerName,

        @NotBlank(message = "mobileNumber is required")
        @Pattern(regexp = "^(?:\\+?91[- ]?)?[6-9](?:[ -]?\\d){9}$",
                message = "mobileNumber must be a valid Indian mobile number")
        String mobileNumber,

        @Pattern(regexp = "^(?:\\+?91[- ]?)?[6-9](?:[ -]?\\d){9}$",
                message = "whatsappNumber must be a valid Indian mobile number")
        String whatsappNumber,

        @Email(message = "email must be valid")
        @Size(max = 255)
        String email,

        @NotNull(message = "source is required")
        Lead.Source source,

        @Size(max = 120)
        String destination,

        UUID tripId,

        @Future(message = "travelDate must be in the future")
        LocalDate travelDate,

        @Min(value = 1, message = "numPersons must be at least 1")
        @Max(value = 50, message = "numPersons must be at most 50")
        Integer numPersons,

        @DecimalMin(value = "0", message = "budget cannot be negative")
        @Digits(integer = 12, fraction = 2)
        BigDecimal budget,

        UUID ownerId,

        @Future(message = "followUpDate must be in the future")
        LocalDate followUpDate,

        @Size(max = 5000, message = "remarks too long")
        String remarks,

        @NotNull(message = "consentGiven is required (DPDPA)")
        Boolean consentGiven,

        @Size(max = 200)
        String consentScope
) {
}