package com.securetravels.crm.trip;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.trip.dto.GuideCreateRequest;
import com.securetravels.crm.trip.dto.GuideResponse;
import com.securetravels.crm.trip.dto.GuideUpdateRequest;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/guides")
public class GuideController {

    private final GuideService guides;

    public GuideController(GuideService guides) {
        this.guides = guides;
    }

    @Operation(summary = "List guides (active by default)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<GuideResponse> list(@RequestParam(defaultValue = "true") boolean active) {
        return guides.list(active);
    }

    @Operation(summary = "Create a guide", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public GuideResponse create(@Valid @RequestBody GuideCreateRequest request, @CurrentUser UserPrincipal caller) {
        return guides.create(request, caller);
    }

    @Operation(summary = "Update a guide", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public GuideResponse update(@PathVariable UUID id, @Valid @RequestBody GuideUpdateRequest request,
                                @CurrentUser UserPrincipal caller) {
        return guides.update(id, request, caller);
    }
}