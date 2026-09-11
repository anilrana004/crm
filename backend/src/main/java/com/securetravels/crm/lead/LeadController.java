package com.securetravels.crm.lead;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.lead.dto.LeadActivityResponse;
import com.securetravels.crm.lead.dto.LeadCreateRequest;
import com.securetravels.crm.lead.dto.LeadResponse;
import com.securetravels.crm.lead.dto.LeadStatusRequest;
import com.securetravels.crm.lead.dto.LeadUpdateRequest;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lead Management (Module 1). Same reference-slice conventions: validated
 * DTOs, @PreAuthorize + service-layer ownership, field-level audit logging,
 * rule-based heat scoring recomputed on every field change.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @Operation(summary = "Create a lead", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public LeadResponse create(@Valid @RequestBody LeadCreateRequest request,
                               @CurrentUser UserPrincipal caller) {
        return leadService.create(request, caller);
    }

    @Operation(summary = "List leads (filtered; SALES/OPS see only their own)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public Page<LeadResponse> list(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) Lead.Status status,
            @RequestParam(required = false) Lead.Source source,
            @RequestParam(required = false) Lead.Heat heat,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) LocalDate travelFrom,
            @RequestParam(required = false) LocalDate travelTo,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserPrincipal caller) {
        return leadService.list(ownerId, status, source, heat, tripId, from, to, travelFrom, travelTo,
                search, page, size, caller);
    }

    @Operation(summary = "Get one lead", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public LeadResponse get(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return leadService.get(id, caller);
    }

    @Operation(summary = "Edit lead fields (heat recomputed, per-field audit)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public LeadResponse update(@PathVariable UUID id,
                               @Valid @RequestBody LeadUpdateRequest request,
                               @CurrentUser UserPrincipal caller) {
        return leadService.update(id, request, caller);
    }

    @Operation(summary = "Lead activity timeline (from audit_log)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/{id}/activity", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<LeadActivityResponse> activity(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return leadService.activity(id, caller);
    }

    @Operation(summary = "Change lead status (audited; LOST requires lostReason)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public LeadResponse updateStatus(@PathVariable UUID id,
                                     @Valid @RequestBody LeadStatusRequest request,
                                     @CurrentUser UserPrincipal caller) {
        return leadService.updateStatus(id, request, caller);
    }
}