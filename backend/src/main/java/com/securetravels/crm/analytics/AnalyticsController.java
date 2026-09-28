package com.securetravels.crm.analytics;

import com.securetravels.crm.analytics.dto.AuditSearchResponse;
import com.securetravels.crm.analytics.dto.CustomerInsightsResponse;
import com.securetravels.crm.analytics.dto.OperationsReadinessResponse;
import com.securetravels.crm.analytics.dto.ReportFilter;
import com.securetravels.crm.analytics.dto.SalesFunnelResponse;
import com.securetravels.crm.analytics.dto.TeamPerformanceResponse;
import com.securetravels.crm.analytics.dto.TripPerformanceResponse;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Module 2 reporting endpoints.
 *
 * <p>Every endpoint takes the same optional filter, so a client can build one
 * filter control set and reuse it across all six reports.
 *
 * <p>{@code @PreAuthorize} here decides WHO may call an endpoint.
 * {@code AnalyticsService.scopeToCaller} decides WHOSE DATA they see, because
 * that depends on a request parameter and cannot be expressed as a role check.
 * The two are not substitutes, and removing either one opens a hole: dropping the
 * annotation would let a caller reach a report their role should not see, and
 * dropping the scoping would let a SALES user read a colleague's revenue by
 * editing {@code consultantId}.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/funnel")
    @PreAuthorize("hasAnyRole('SALES', 'OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public SalesFunnelResponse funnel(
            @RequestParam(required = false) java.util.UUID consultantId,
            @RequestParam(required = false) java.util.UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Season season,
            @RequestParam(required = false) String source,
            @CurrentUser UserPrincipal caller) {
        return service.funnel(
                new ReportFilter(consultantId, tripId, from, to, season, source), caller);
    }

    @GetMapping("/trips")
    @PreAuthorize("hasAnyRole('SALES', 'OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public TripPerformanceResponse trips(
            @RequestParam(required = false) java.util.UUID consultantId,
            @RequestParam(required = false) java.util.UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Season season,
            @RequestParam(required = false) String source,
            @CurrentUser UserPrincipal caller) {
        return service.tripPerformance(
                new ReportFilter(consultantId, tripId, from, to, season, source), caller);
    }

    /**
     * A SALES caller is allowed in and then scoped to their own row by the
     * service. Denying the role outright would be wrong: "how am I doing" is the
     * single most common report a consultant opens.
     */
    @GetMapping("/team")
    @PreAuthorize("hasAnyRole('SALES', 'OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public TeamPerformanceResponse team(
            @RequestParam(required = false) java.util.UUID consultantId,
            @RequestParam(required = false) java.util.UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Season season,
            @RequestParam(required = false) String source,
            @CurrentUser UserPrincipal caller) {
        return service.teamPerformance(
                new ReportFilter(consultantId, tripId, from, to, season, source), caller);
    }

    /** Operations-only readiness; a consultant has no business reading vendor gaps. */
    @GetMapping("/operations")
    @PreAuthorize("hasAnyRole('OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public OperationsReadinessResponse operations(
            @RequestParam(required = false) java.util.UUID consultantId,
            @RequestParam(required = false) java.util.UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Season season,
            @RequestParam(required = false) String source,
            @CurrentUser UserPrincipal caller) {
        return service.operationsReadiness(
                new ReportFilter(consultantId, tripId, from, to, season, source), caller);
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('SALES', 'OPS', 'MANAGER', 'ADMIN', 'CEO')")
    public CustomerInsightsResponse customers(
            @RequestParam(required = false) java.util.UUID consultantId,
            @RequestParam(required = false) java.util.UUID tripId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Season season,
            @RequestParam(required = false) String source,
            @CurrentUser UserPrincipal caller) {
        return service.customerInsights(
                new ReportFilter(consultantId, tripId, from, to, season, source), caller);
    }

    /**
     * Audit read access is ADMIN and CEO only.
     *
     * <p>{@code audit_log} records the old and new value of every mutation, so it
     * contains customer contact details and payment amounts that no individual
     * consultant needs to see, including changes to records they do not own.
     * Unlike the other reports there is no per-caller narrowing that would make
     * this safe for a broad role, so the boundary is drawn at the endpoint.
     */
    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('ADMIN', 'CEO')")
    public AuditSearchResponse audit(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) java.util.UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @CurrentUser UserPrincipal caller) {
        return service.searchAudit(q, entity, actorId, from, to, page, size, caller);
    }
}
