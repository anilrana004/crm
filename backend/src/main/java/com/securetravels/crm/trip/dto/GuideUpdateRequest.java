package com.securetravels.crm.trip.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Partial guide update; {@code null} fields are left untouched. */
public record GuideUpdateRequest(
        @Size(max = 120, message = "fullName too long")
        String fullName,

        @Pattern(regexp = "^(?:\\+?91[- ]?)?[6-9](?:[ -]?\\d){9}$",
                message = "phone must be a valid Indian mobile number")
        String phone,

        @DecimalMin(value = "0.0", message = "dailyRate must not be negative")
        BigDecimal dailyRate,

        Boolean active
) {
}