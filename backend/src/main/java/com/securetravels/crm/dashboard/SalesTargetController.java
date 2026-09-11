package com.securetravels.crm.dashboard;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.dashboard.dto.TargetDto;
import com.securetravels.crm.dashboard.dto.TargetListResponse;
import com.securetravels.crm.dashboard.dto.TargetProgressResponse;
import com.securetravels.crm.dashboard.dto.TargetUpsertRequest;
import com.securetravels.crm.user.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Module 6 sales-target tracking: reads for all, writes for management. */
@RestController
@RequestMapping("/api/targets")
public class SalesTargetController {

    private final DashboardService service;

    public SalesTargetController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public TargetListResponse list(@RequestParam(required = false) String month,
                                   @CurrentUser UserPrincipal caller) {
        return service.targets(month, caller);
    }

    @GetMapping("/progress")
    @PreAuthorize("isAuthenticated()")
    public TargetProgressResponse progress(@RequestParam(required = false) String month,
                                           @CurrentUser UserPrincipal caller) {
        return service.progress(month, caller);
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'CEO')")
    public TargetDto upsert(@Valid @RequestBody TargetUpsertRequest req,
                            @CurrentUser UserPrincipal caller) {
        return service.upsertTarget(req, caller);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'CEO')")
    public void delete(@PathVariable UUID id) {
        service.deleteTarget(id);
    }
}