package com.securetravels.crm.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TravellerInput(
        @NotBlank(message = "fullName is required")
        @Size(max = 200, message = "fullName too long")
        String fullName,

        Integer age,

        @Size(max = 10, message = "gender too long")
        String gender,

        @Size(max = 20, message = "phone too long")
        String phone,

        Boolean medicalCertRequired
) {
    public boolean medicalCertFlag() {
        return medicalCertRequired != null && medicalCertRequired;
    }
}