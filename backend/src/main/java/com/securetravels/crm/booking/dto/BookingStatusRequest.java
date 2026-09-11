package com.securetravels.crm.booking.dto;

import com.securetravels.crm.booking.Booking;
import jakarta.validation.constraints.NotNull;

public record BookingStatusRequest(
        @NotNull(message = "status is required")
        Booking.Status status,

        String note
) {
}