package com.securetravels.crm.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Booking creation. Either {@code customerId} or {@code leadId} must be
 * given (checked in the service); a lead starts from the lead's linked
 * Customer360, created from the lead's consented PII when needed.
 */
public record BookingCreateRequest(
        UUID customerId,

        UUID leadId,

        @NotNull(message = "tripId is required")
        UUID tripId,

        UUID batchId,

        LocalDate travelDate,

        @Min(value = 1, message = "numTravellers must be at least 1")
        Integer numTravellers,

        @DecimalMin(value = "0.0", message = "discountAmount must not be negative")
        BigDecimal discountAmount,

        String notes,

        List<TravellerInput> travellers
) {
}