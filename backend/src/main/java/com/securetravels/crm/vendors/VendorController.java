package com.securetravels.crm.vendors;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.vendors.dto.VendorCreateRequest;
import com.securetravels.crm.vendors.dto.VendorResponse;
import com.securetravels.crm.vendors.dto.VendorUpdateRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendors;

    public VendorController(VendorService vendors) {
        this.vendors = vendors;
    }

    @Operation(summary = "List vendors (optional category filter; active by default)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<VendorResponse> list(@RequestParam(required = false) Vendor.Category category,
                                     @RequestParam(defaultValue = "true") boolean active) {
        return vendors.list(category, active);
    }

    @Operation(summary = "Get a vendor", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public VendorResponse get(@PathVariable UUID id) {
        return vendors.get(id);
    }

    @Operation(summary = "Create a vendor", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public VendorResponse create(@Valid @RequestBody VendorCreateRequest request,
                                 @CurrentUser UserPrincipal caller) {
        return vendors.create(request, caller);
    }

    @Operation(summary = "Update a vendor", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public VendorResponse update(@PathVariable UUID id, @Valid @RequestBody VendorUpdateRequest request,
                                 @CurrentUser UserPrincipal caller) {
        return vendors.update(id, request, caller);
    }
}