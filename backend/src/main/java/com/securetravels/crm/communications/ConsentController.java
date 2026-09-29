package com.securetravels.crm.communications;

import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.consent.ConsentSource;
import com.securetravels.crm.communications.consent.ConsentStatus;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.dto.ConsentRequest;
import com.securetravels.crm.communications.dto.ConsentResponse;
import com.securetravels.crm.customer.CustomerContactDirectory;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Consent & preference surface (Phase 5 Module 1). Readable by anyone who can
 * read the customer; recordable only by staff who can maintain customer flags.
 */
@RestController
@RequestMapping("/api/consent")
@Tag(name = "Consent")
@SecurityRequirement(name = "bearerAuth")
public class ConsentController {

    private static final Set<Role> WRITERS = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO);

    private final ConsentService consent;
    private final CustomerContactDirectory customers;
    private final TimelineService timeline;

    public ConsentController(ConsentService consent, CustomerContactDirectory customers,
                             TimelineService timeline) {
        this.consent = consent;
        this.customers = customers;
        this.timeline = timeline;
    }

    @Operation(summary = "Current MARKETING consent per channel for a customer",
            description = "GRANTED is the only state that lets the send gate deliver a marketing message "
                    + "on that channel; REVOKED and UNKNOWN both block it.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ConsentResponse status(@RequestParam UUID customerId, @CurrentUser UserPrincipal caller) {
        requireCustomer(customerId);
        timeline.assertCanRead(SubjectType.CUSTOMER, customerId, caller);
        return new ConsentResponse(null, customerId, consent.marketingStatus(customerId));
    }

    @Operation(summary = "Record an explicit consent instruction",
            description = "Writes an append-only consent_records row. Only GRANTED or REVOKED can be "
                    + "recorded; UNKNOWN is a system state that staff should never type.")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ConsentResponse record(@Valid @RequestBody ConsentRequest request,
                                  @CurrentUser UserPrincipal caller) {
        requireWriter(caller.role());
        requireCustomer(request.customerId());
        timeline.assertCanRead(SubjectType.CUSTOMER, request.customerId(), caller);

        Purpose purpose = request.purpose() == null ? Purpose.MARKETING : request.purpose();
        if (request.status() == ConsentStatus.UNKNOWN) {
            throw new BadRequestException("UNKNOWN consent cannot be recorded via the API; "
                    + "it is a system default");
        }
        ConsentSource source = ConsentSource.STAFF_RECORDED;
        UUID recordId = request.status() == ConsentStatus.GRANTED
                ? consent.grant(request.customerId(), request.channel(), purpose, source,
                    request.evidence(), caller.id())
                : consent.revoke(request.customerId(), request.channel(), purpose, source,
                    request.evidence(), caller.id());
        return new ConsentResponse(recordId, request.customerId(),
                consent.marketingStatus(request.customerId()));
    }

    private void requireCustomer(UUID customerId) {
        if (!this.customers.exists(customerId)) {
            throw new NotFoundException("Customer not found: " + customerId);
        }
    }

    private static void requireWriter(Role role) {
        if (!WRITERS.contains(role)) {
            throw new ForbiddenException("This role (" + role + ") cannot record consent");
        }
    }
}