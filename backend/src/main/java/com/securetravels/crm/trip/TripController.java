package com.securetravels.crm.trip;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.trip.dto.BatchCreateRequest;
import com.securetravels.crm.trip.dto.BatchGenerateRequest;
import com.securetravels.crm.trip.dto.BatchGenerateResponse;
import com.securetravels.crm.trip.dto.BatchResponse;
import com.securetravels.crm.trip.dto.TripCreateRequest;
import com.securetravels.crm.trip.dto.TripDetailResponse;
import com.securetravels.crm.trip.dto.TripResponse;
import com.securetravels.crm.trip.dto.TripUpdateRequest;
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

/**
 * Trip catalogue + departure batches. Reads are open to any authenticated
 * user (leads filters, booking flow); writes are Manager/Admin/CEO.
 */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @Operation(summary = "List trips (default: active only, for filters/pickers)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<TripResponse> list(@RequestParam(defaultValue = "true") boolean active) {
        return trips.list(active).stream()
                .map(t -> new TripResponse(t.getId(), t.getName(), t.getSlug(), t.getCategory(),
                        t.getBookingType(), t.getDifficulty(), t.getBaseCost(), t.getDurationDays(), t.isActive()))
                .toList();
    }

    @Operation(summary = "Get a trip with its departure batches",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public TripDetailResponse get(@PathVariable UUID id) {
        return trips.get(id);
    }

    @Operation(summary = "Create a catalogue trip", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public TripDetailResponse create(@Valid @RequestBody TripCreateRequest request, @CurrentUser UserPrincipal caller) {
        return trips.create(request, caller);
    }

    @Operation(summary = "Update a trip", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public TripDetailResponse update(@PathVariable UUID id, @Valid @RequestBody TripUpdateRequest request,
                                     @CurrentUser UserPrincipal caller) {
        return trips.update(id, request, caller);
    }

    @Operation(summary = "List batches of a trip", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(value = "/{tripId}/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<BatchResponse> listBatches(@PathVariable UUID tripId) {
        return trips.listBatches(tripId);
    }

    @Operation(summary = "Create a departure batch on a FIXED_BATCH trip",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(value = "/{tripId}/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BatchResponse createBatch(@PathVariable UUID tripId, @Valid @RequestBody BatchCreateRequest request,
                                     @CurrentUser UserPrincipal caller) {
        return trips.createBatch(tripId, request, caller);
    }

    @Operation(summary = "Generate a season of departure batches from a recurrence rule",
            description = "Module 3. Expands the rule to concrete dates and creates one batch per date in a "
                    + "single transaction. Dates that already have a batch are reported in skippedDates "
                    + "rather than failing the run, so an existing season can be extended.")
    @PostMapping(value = "/{tripId}/batches/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BatchGenerateResponse generateSeason(@PathVariable UUID tripId,
                                                @Valid @RequestBody BatchGenerateRequest request,
                                                @CurrentUser UserPrincipal caller) {
        return trips.generateSeason(tripId, request, caller);
    }
}