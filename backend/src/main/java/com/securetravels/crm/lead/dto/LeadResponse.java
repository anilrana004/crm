package com.securetravels.crm.lead.dto;

import com.securetravels.crm.lead.Lead;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeadResponse(
        UUID id,
        String customerName,
        String mobileNumber,
        String whatsappNumber,
        String email,
        Lead.Source source,
        String destination,
        UUID tripId,
        LocalDate travelDate,
        int numPersons,
        BigDecimal budget,
        UUID ownerId,
        String ownerName,
        UUID customer360Id,
        Lead.Status status,
        Lead.Heat heat,
        LocalDate followUpDate,
        String remarks,
        boolean consentGiven,
        String consentScope,
        Instant consentCapturedAt,
        Lead.LostReason lostReason,
        UUID duplicateOfLeadId,
        Instant lastContactedAt,
        Instant createdAt,
        Instant updatedAt
) {
}