package com.securetravels.crm.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Achievement view (legacy M6 progress): overall + each targeted employee. */
public record TargetProgressResponse(
        LocalDate month,
        TargetListResponse.Overall overall,
        List<TargetDto> employees) {
}