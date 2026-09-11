package com.securetravels.crm.operations;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.operations.dto.OpsArrangementsRequest;
import com.securetravels.crm.operations.dto.OpsNoteRequest;
import com.securetravels.crm.operations.dto.OperationsHandoffResponse;
import com.securetravels.crm.payment.Payment;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Operations (Module 6). The handoff is created automatically when a booking
 * is confirmed; this surface is the ops-team dashboard: arrangements,
 * guide/driver assignment, running notes, trip sheet, and a live view of the
 * receivable status.
 */
@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final OperationsService operations;

    public OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @Operation(summary = "List ops handoffs (SALES see only their own bookings' handoffs; filters optional)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<OperationsHandoffResponse> list(@RequestParam(required = false) LocalDate travelDateFrom,
                                                @RequestParam(required = false) LocalDate travelDateTo,
                                                @RequestParam(required = false) OperationsHandoff.HandoffStatus hotelStatus,
                                                @RequestParam(required = false) OperationsHandoff.HandoffStatus transportStatus,
                                                @RequestParam(required = false) Payment.Status paymentStatus,
                                                @CurrentUser UserPrincipal caller) {
        return operations.list(travelDateFrom, travelDateTo, hotelStatus, transportStatus, paymentStatus, caller);
    }

    @Operation(summary = "Get one ops handoff (booking context, arrangements, receivable status, notes)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public OperationsHandoffResponse get(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return operations.get(id, caller);
    }

    @Operation(summary = "Update arrangements: hotel / transport status, guide, driver",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/arrangements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public OperationsHandoffResponse updateArrangements(@PathVariable UUID id,
                                                        @Valid @RequestBody OpsArrangementsRequest request,
                                                        @CurrentUser UserPrincipal caller) {
        return operations.updateArrangements(id, request, caller);
    }

    @Operation(summary = "Append a timestamped note to the handoff",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(path = "/{id}/notes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public OperationsHandoffResponse appendNote(@PathVariable UUID id,
                                                @Valid @RequestBody OpsNoteRequest request,
                                                @CurrentUser UserPrincipal caller) {
        return operations.appendNote(id, request, caller);
    }

    @Operation(summary = "Mark the trip sheet as generated (regenerating refreshes the timestamp)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(path = "/{id}/trip-sheet", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public OperationsHandoffResponse generateTripSheet(@PathVariable UUID id,
                                                       @CurrentUser UserPrincipal caller) {
        return operations.generateTripSheet(id, caller);
    }
}