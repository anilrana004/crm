package com.securetravels.crm.booking.dto;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.trip.BookingType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read model: booking + resolved names/dates + travellers + net amount (I3). */
public record BookingResponse(
        UUID id,
        String bookingRef,
        UUID tripId,
        String tripName,
        UUID batchId,
        LocalDate departureDate,
        UUID customerId,
        String customerName,
        String customerPhone,
        BookingType bookingType,
        int numTravellers,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        Booking.Status status,
        LocalDate travelDate,
        UUID discountApprovedBy,
        String notes,
        UUID leadId,
        UUID createdBy,
        String createdByName,
        List<TravellerResponse> travellers,
        Instant createdAt,
        Instant updatedAt
) {
}