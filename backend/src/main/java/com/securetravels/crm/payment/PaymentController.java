package com.securetravels.crm.payment;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.payment.dto.PaymentCreateRequest;
import com.securetravels.crm.payment.dto.PaymentResponse;
import com.securetravels.crm.payment.dto.PaymentStatusRequest;
import com.securetravels.crm.payment.dto.PaymentSummaryResponse;
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
 * Payments (Module 5). Sales record receipts for their own bookings;
 * managers/ops see everything; ops can read statements but not record.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @Operation(summary = "Record a payment line (advance / balance / full) against a booking",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public PaymentResponse record(@Valid @RequestBody PaymentCreateRequest request,
                                  @CurrentUser UserPrincipal caller) {
        return payments.record(request, caller);
    }

    @Operation(summary = "List payment lines (scoped; booking / status / type filters)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<PaymentResponse> list(@RequestParam(required = false) UUID bookingId,
                                      @RequestParam(required = false) Payment.Status status,
                                      @RequestParam(required = false) Payment.AmountType amountType,
                                      @CurrentUser UserPrincipal caller) {
        return payments.list(bookingId, status, amountType, caller);
    }

    @Operation(summary = "Receivable statement for a booking (gross, net, applied, balance)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(path = "/booking/{bookingId}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public PaymentSummaryResponse summary(@PathVariable UUID bookingId, @CurrentUser UserPrincipal caller) {
        return payments.summary(bookingId, caller);
    }

    @Operation(summary = "Advance a payment line: PENDING -> PARTIAL/COMPLETED/CANCELLED, "
            + "PARTIAL -> COMPLETED/REFUNDED, COMPLETED -> REFUNDED (paidAt required for COMPLETED)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('SALES', 'MANAGER', 'ADMIN', 'CEO')")
    public PaymentResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody PaymentStatusRequest request,
                                        @CurrentUser UserPrincipal caller) {
        return payments.transition(id, request, caller);
    }
}