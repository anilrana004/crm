package com.securetravels.crm.booking;

import com.securetravels.crm.booking.dto.BookingCreateRequest;
import com.securetravels.crm.booking.dto.BookingResponse;
import com.securetravels.crm.booking.dto.BookingStatusRequest;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/**
 * Bookings (Module 4). Sales and managers create; sales confirm/cancel the
 * bookings they made; managers additionally approve discounts and see all.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @Operation(summary = "Create a booking (FIXED_BATCH holds seats; CUSTOM_FIT is private)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public BookingResponse create(@Valid @RequestBody BookingCreateRequest request,
                                  @CurrentUser UserPrincipal caller) {
        return bookings.create(request, caller);
    }

    @Operation(summary = "List bookings (SALES see only their own; filters optional)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<BookingResponse> list(@RequestParam(required = false) UUID tripId,
                                      @RequestParam(required = false) UUID batchId,
                                      @RequestParam(required = false) UUID customerId,
                                      @RequestParam(required = false) Booking.Status status,
                                      @CurrentUser UserPrincipal caller) {
        return bookings.list(tripId, batchId, customerId, status, caller);
    }

    @Operation(summary = "Get a booking with travellers", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public BookingResponse get(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return bookings.get(id, caller);
    }

    @Operation(summary = "Advance a booking: QUOTATION -> CONFIRMED (seats), -> CANCELLED (seats released), CONFIRMED -> COMPLETED",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public BookingResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody BookingStatusRequest request,
                                        @CurrentUser UserPrincipal caller) {
        return bookings.updateStatus(id, request, caller);
    }
}