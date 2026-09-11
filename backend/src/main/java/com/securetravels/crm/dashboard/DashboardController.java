package com.securetravels.crm.dashboard;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.dashboard.dto.DashboardSummaryResponse;
import com.securetravels.crm.dashboard.dto.PerformanceResponse;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module 4/5 dashboard: aggregate summary cards + per-employee performance.
 * All authenticated users (roles see their own data via scoping in the service).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public DashboardSummaryResponse summary(
            @RequestParam(required = false) String period,
            @CurrentUser UserPrincipal caller) {
        return service.summary(period, caller);
    }

    @GetMapping("/performance")
    @PreAuthorize("isAuthenticated()")
    public PerformanceResponse performance(
            @RequestParam(required = false) String month,
            @CurrentUser UserPrincipal caller) {
        return service.performance(month, caller);
    }
}