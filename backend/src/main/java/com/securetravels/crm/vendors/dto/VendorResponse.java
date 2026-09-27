package com.securetravels.crm.vendors.dto;

import com.securetravels.crm.vendors.Vendor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Vendor catalogue read model. */
public record VendorResponse(
        UUID id,
        Vendor.Category category,
        String name,
        String phone,
        String email,
        String city,
        String gstin,
        String bankAccountRef,
        BigDecimal dailyRate,
        String notes,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}