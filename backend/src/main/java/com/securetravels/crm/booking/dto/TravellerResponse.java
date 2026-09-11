package com.securetravels.crm.booking.dto;

import java.util.UUID;

public record TravellerResponse(
        UUID id,
        String fullName,
        Integer age,
        String gender,
        String phone,
        boolean medicalCertRequired
) {
}