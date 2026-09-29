package com.securetravels.crm.customer.dto;

import com.securetravels.crm.booking.Booking.Status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Full customer view incl. trip-history timeline and marketing/offer flags. */
public record CustomerDetailResponse(
        UUID id,
        String fullName,
        String mobileNumber,
        String whatsappNumber,
        String email,
        boolean consentGiven,
        Instant consentCapturedAt,
        String consentScope,
        boolean marketingOptIn,
        int totalTrips,
        LocalDate lastTripDate,
        BigDecimal totalSpent,
        String suggestOffer,
        String[] offerTags,
        String notes,
        List<TripRow> tripHistory,
        Map<String, String> marketingConsent,
        Instant createdAt,
        Instant updatedAt
) {
    public record TripRow(
            UUID id,
            String bookingRef,
            UUID tripId,
            String tripName,
            LocalDate travelDate,
            Status status,
            BigDecimal netAmount,
            BigDecimal appliedAmount
    ) {
    }
}
