package com.securetravels.crm.lead.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lead field update (PATCH semantics: only provided fields are changed).
 * The enquiry fields a consultant may correct on the Lead Detail page.
 * Mobile number stays immutable (it is the verified dedup identity — a
 * number change is a new enquiry). Heat is recomputed by the service.
 */
public record LeadUpdateRequest(
        @Size(max = 200, message = "customerName too long")
        String customerName,

        @Size(max = 255) @Email(message = "email must be valid")
        String email,

        @Pattern(regexp = "^(?:\\+?91[- ]?)?[6-9](?:[ -]?\\d){9}$",
                message = "whatsappNumber must be a valid Indian mobile number")
        String whatsappNumber,

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

        @Future(message = "followUpDate must be in the future")
        LocalDate followUpDate,

        @Size(max = 5000, message = "remarks too long")
        String remarks
) {
}