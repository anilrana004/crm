package com.securetravels.crm.vendors.dto;

import com.securetravels.crm.vendors.Vendor;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Vendor creation payload. */
public record VendorCreateRequest(
        @NotNull(message = "category is required")
        Vendor.Category category,

        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name too long")
        String name,

        @Pattern(regexp = "^(?:\\+?91[- ]?)?[6-9](?:[ -]?\\d){9}$",
                message = "phone must be a valid Indian mobile number")
        String phone,

        @Email(message = "email must be a valid address")
        @Size(max = 120, message = "email too long")
        String email,

        @Size(max = 80, message = "city too long")
        String city,

        @Pattern(regexp = "^[0-9A-Za-z]{15}$",
                message = "gstin must be exactly 15 alphanumeric characters")
        String gstin,

        @Size(max = 40, message = "bankAccountRef too long")
        String bankAccountRef,

        @DecimalMin(value = "0.0", message = "dailyRate must not be negative")
        BigDecimal dailyRate,

        @Size(max = 4000, message = "notes too long")
        String notes
) {
}