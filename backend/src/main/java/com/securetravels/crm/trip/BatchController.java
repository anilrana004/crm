package com.securetravels.crm.trip;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.trip.dto.BatchResponse;
import com.securetravels.crm.trip.dto.BatchUpdateRequest;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final TripService trips;

    public BatchController(TripService trips) {
        this.trips = trips;
    }

    @Operation(summary = "Update a departure batch (capacity/date/guide/transport/status)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BatchResponse update(@PathVariable UUID id, @Valid @RequestBody BatchUpdateRequest request,
                                @CurrentUser UserPrincipal caller) {
        return trips.updateBatch(id, request, caller);
    }
}