package com.securetravels.crm.customer;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.customer.dto.CustomerDetailResponse;
import com.securetravels.crm.customer.dto.CustomerListResponse;
import com.securetravels.crm.customer.dto.CustomerUpdateRequest;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Customer Database (Module 13): the retained customer directory (Customer360)
 * with trip-history timeline and marketing/remarketing flags. Read for any
 * authenticated user; flag/note maintenance is MANAGER/ADMIN/CEO.
 */
@RestController
@RequestMapping("/api/customers")
public class Customer360Controller {

    private final Customer360Service customers;

    public Customer360Controller(Customer360Service customers) {
        this.customers = customers;
    }

    @Operation(summary = "List customers — search by name/mobile/email",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<CustomerListResponse> list(@RequestParam(required = false) String search,
                                           @CurrentUser UserPrincipal caller) {
        return customers.list(search, caller);
    }

    @Operation(summary = "Customer detail incl. trip history + marketing flags",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public CustomerDetailResponse get(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return customers.get(id, caller);
    }

    @Operation(summary = "Update marketing flags: suggest-offer, remarketing tags, opt-in, notes",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'CEO')")
    public CustomerDetailResponse update(@PathVariable UUID id,
                                         @Valid @RequestBody CustomerUpdateRequest request,
                                         @CurrentUser UserPrincipal caller) {
        return customers.update(id, request, caller);
    }
}