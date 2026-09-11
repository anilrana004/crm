package com.securetravels.crm.dashboard.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/** Set / update a monthly target. userId null = company-wide target. */
public record TargetUpsertRequest(
        UUID userId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "month must be YYYY-MM") String month,
        @Min(0) Integer targetBookings,
        @DecimalMin("0.00") BigDecimal targetRevenue
) {
}