package com.securetravels.crm.document;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.document.dto.BatchComplianceResponse;
import com.securetravels.crm.document.dto.MarkChecklistRequest;
import com.securetravels.crm.document.dto.TravellerComplianceResponse;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 2 Module 1 — compliance board + the READY_FOR_DEPARTURE hard gate.
 * Reads (board views) are open to any authenticated user; writes and the
 * gate are OPS/manager only.
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService compliance;

    public ComplianceController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @Operation(summary = "Per-traveller checklist with required/verified items and %",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/travellers/{travellerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public TravellerComplianceResponse traveller(@PathVariable UUID travellerId) {
        return compliance.travellerSummary(travellerId);
    }

    @Operation(summary = "Mark checklist items (VERIFIED is the green state; demote allowed)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping(value = "/travellers/{travellerId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public TravellerComplianceResponse mark(@PathVariable UUID travellerId,
                                            @Valid @RequestBody MarkChecklistRequest request,
                                            @CurrentUser UserPrincipal caller) {
        return compliance.mark(travellerId, request, caller);
    }

    @Operation(summary = "Batch compliance board (confirmed bookings only)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/batches/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BatchComplianceResponse batch(@PathVariable UUID batchId) {
        return compliance.batchSummary(batchId);
    }

    @Operation(summary = "Check whether the batch currently passes the READY_FOR_DEPARTURE gate",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/batches/{batchId}/ready-for-departure", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BatchComplianceResponse readyCheck(@PathVariable UUID batchId) {
        return compliance.readyCheck(batchId);
    }

    @Operation(summary = "Mark the batch READY_FOR_DEPARTURE (409 if compliance is below the gate)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/batches/{batchId}/ready-for-departure", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public BatchComplianceResponse markReadyForDeparture(@PathVariable UUID batchId,
                                                         @CurrentUser UserPrincipal caller) {
        return compliance.markReadyForDeparture(batchId, caller);
    }
}