package com.securetravels.crm.lead;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditLog;
import com.securetravels.crm.common.audit.AuditLogRepository;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.dto.LeadActivityResponse;
import com.securetravels.crm.lead.dto.LeadCreateRequest;
import com.securetravels.crm.lead.dto.LeadResponse;
import com.securetravels.crm.lead.dto.LeadStatusRequest;
import com.securetravels.crm.lead.dto.LeadUpdateRequest;
import com.securetravels.crm.task.FollowUpAutomation;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LeadService {

    private static final Set<Role> SEES_ALL = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO);

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LeadService.class);

    /** Allowed status transitions (business rules). LOST is absorbing; a confirmed
     *  booking can only be reopened to QUOTATION_SENT by its cancellation. */
    private static final Map<Lead.Status, Set<Lead.Status>> TRANSITIONS = Map.of(
            Lead.Status.NEW, EnumSet.of(Lead.Status.INTERESTED, Lead.Status.QUOTATION_SENT, Lead.Status.LOST),
            Lead.Status.INTERESTED, EnumSet.of(Lead.Status.QUOTATION_SENT, Lead.Status.LOST),
            Lead.Status.QUOTATION_SENT, EnumSet.of(Lead.Status.INTERESTED, Lead.Status.BOOKING_CONFIRMED, Lead.Status.LOST),
            Lead.Status.BOOKING_CONFIRMED, EnumSet.of(Lead.Status.QUOTATION_SENT),
            Lead.Status.LOST, EnumSet.noneOf(Lead.Status.class));

    private final LeadRepository leads;
    private final UserRepository users;
    private final Customer360Repository customers;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final LeadScoringService scoringService;
    private final FollowUpAutomation automation;
    private final com.securetravels.crm.webhook.RoundRobinService roundRobin;

    public LeadService(LeadRepository leads, UserRepository users, Customer360Repository customers,
                       AuditLogRepository auditLogRepository, AuditService auditService,
                       LeadScoringService scoringService, FollowUpAutomation automation,
                       com.securetravels.crm.webhook.RoundRobinService roundRobin) {
        this.leads = leads;
        this.users = users;
        this.customers = customers;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.scoringService = scoringService;
        this.automation = automation;
        this.roundRobin = roundRobin;
    }

    @Transactional
    public LeadResponse create(LeadCreateRequest request, UserPrincipal caller) {
        requireRole(caller.role(), Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);
        UUID ownerId = request.ownerId() != null ? request.ownerId() : caller.id();
        return createInternal(request, ownerId, caller.id());
    }

    /**
     * Webhook path (Module 9): same create pipeline, but the owner is chosen by
     * the round-robin service and the actor is the system (no SecurityContext).
     */
    @Transactional
    public LeadResponse createAutomated(LeadCreateRequest request, UUID ownerId) {
        return createInternal(request, ownerId, null);
    }

    private LeadResponse createInternal(LeadCreateRequest request, UUID ownerId, UUID createdBy) {
        // DPDPA: explicit consent is mandatory to hold a prospect's PII.
        if (request.consentGiven() == null || !request.consentGiven()) {
            throw new BadRequestException("Explicit consent (consentGiven=true) is required to create a lead");
        }
        String digits = normalizedDigits(request.mobileNumber());

        // Duplicate detection by normalized phone (non-LOST leads only).
        Lead duplicate = leads.findFirstActiveDuplicate(digits).orElse(null);
        if (duplicate != null) {
            throw new ConflictException("A lead with this mobile number already exists (id=" + duplicate.getId()
                    + ", status=" + duplicate.getStatus() + ")");
        }

        // Returning customer? Link to the canonical record instead of leaving a
        // disconnected lead, and log a "New Trip Interest" on their timeline.
        Customer360 customer = customers.findByMobileDigits(digits).orElse(null);

        Lead lead = new Lead();
        lead.setCustomerName(request.customerName());
        lead.setMobileNumber(request.mobileNumber());
        lead.setMobileDigits(digits);
        lead.setWhatsappNumber(request.whatsappNumber());
        lead.setEmail(request.email());
        lead.setSource(request.source());
        lead.setDestination(request.destination());
        lead.setTripId(request.tripId());
        lead.setTravelDate(request.travelDate());
        lead.setNumPersons(request.numPersons() == null ? 1 : request.numPersons());
        lead.setBudget(request.budget());
        lead.setFollowUpDate(request.followUpDate());
        lead.setRemarks(request.remarks());          // OWASP-sanitized inside the setter
        lead.setOwnerId(ownerId);
        lead.setCreatedBy(createdBy);
        lead.setStatus(Lead.Status.NEW);
        lead.setConsentGiven(true);
        lead.setConsentCapturedAt(Instant.now());
        lead.setConsentScope(request.consentScope() == null
                ? "contact for travel enquiry and follow-up" : request.consentScope());
        if (customer != null) {
            lead.setCustomer360Id(customer.getId());
        }
        lead.setHeat(scoringService.score(lead));

        Lead saved = leads.save(lead);
        auditService.record("LEAD", saved.getId(),
                com.securetravels.crm.common.audit.AuditAction.CREATE, "lead", null, saved.getId().toString());
        if (customer != null) {
            auditService.record("CUSTOMER360", customer.getId(),
                    com.securetravels.crm.common.audit.AuditAction.UPDATE, "new_trip_interest", null,
                    saved.getId().toString());
        }
        automation.onLeadCreated(saved.getId(), saved.getOwnerId());
        return toResponse(saved);
    }

    /**
     * A lead created because the person contacted <em>us</em> first — an inbound
     * WhatsApp or SMS message from a number we have no record of.
     *
     * <p>This exists instead of the communications layer constructing a
     * {@link Lead} directly, for two reasons:
     *
     * <ol>
     *   <li><b>It is not a DPDPA bypass.</b> {@link #createInternal} refuses any
     *       lead without an explicit consent flag, and that guard must not be
     *       circumvented by a caller that "knows better". Here the basis is
     *       recorded honestly: the person initiated contact, which is the
     *       consent needed to hold their number in order to answer them. The
     *       recorded scope is deliberately narrow — replying to this enquiry —
     *       and <strong>grants no marketing consent whatsoever</strong>.
     *       Marketing stays UNKNOWN until they opt in, so an inbound "hi" can
     *       never become a promotional send.</li>
     *   <li><b>Duplicate detection still applies.</b> The same
     *       {@code findFirstActiveDuplicate} check runs, and an existing active
     *       lead is returned rather than duplicated, so three gateway retries
     *       cannot produce three leads.</li>
     * </ol>
     *
     * @return the lead to attach the inbound message to, existing or new.
     */
    @Transactional
    public Lead findOrCreateFromInbound(String mobile, Lead.Source source, String inboundSummary) {
        String digits = normalizedDigits(mobile);
        Lead existing = leads.findFirstActiveDuplicate(digits).orElse(null);
        if (existing != null) {
            existing.setLastContactedAt(Instant.now());
            log.info("[lead] inbound message matched existing lead {} for {}", existing.getId(), digits);
            return leads.save(existing);
        }

        Customer360 customer = customers.findByMobileDigits(digits).orElse(null);
        Lead lead = new Lead();
        // No name is known from a phone number; the "Unknown" placeholder is
        // replaced the moment a human or the customer supplies a real one.
        lead.setCustomerName("Unknown");
        lead.setMobileNumber(mobile);
        lead.setMobileDigits(digits);
        lead.setWhatsappNumber(digits);
        lead.setSource(source);
        lead.setOwnerId(pickInboundOwner());
        lead.setStatus(Lead.Status.NEW);
        lead.setConsentGiven(true);
        lead.setConsentCapturedAt(Instant.now());
        lead.setConsentScope("reply to inbound enquiry only; NOT marketing");
        lead.setRemarks(inboundSummary);
        lead.setLastContactedAt(Instant.now());
        if (customer != null) {
            lead.setCustomer360Id(customer.getId());
        }
        lead.setHeat(scoringService.score(lead));

        Lead saved = leads.save(lead);
        auditService.record("LEAD", saved.getId(), AuditAction.CREATE,
                "lead_from_inbound", source.name(), saved.getId().toString());
        if (saved.getOwnerId() != null) {
            automation.onLeadCreated(saved.getId(), saved.getOwnerId());
        }
        log.info("[lead] created lead {} from inbound {} message for {} (owner={})",
                saved.getId(), source, digits, saved.getOwnerId());
        return saved;
    }

    /**
     * The id of the active (non-LOST) lead for a mobile number, if there is one.
     *
     * <p>Exists so other features can resolve "who does this number belong to"
     * without autowiring {@link LeadRepository}. Returns only the identifier:
     * a caller that needs lead state should go through {@link LeadService},
     * which is where the rules about it live.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveLeadIdByMobile(String mobile) {
        String digits = normalizedDigits(mobile);
        if (digits == null) {
            return Optional.empty();
        }
        return leads.findFirstActiveDuplicate(digits).map(Lead::getId);
    }

    /**
     * Who should own a lead that arrived by itself.
     *
     * <p>Round-robin, the same rule the website webhook already uses — an
     * inbound enquiry is a lead like any other and must not sit unowned in a
     * queue nobody watches.
     *
     * <p>Returns null when there is nobody available. That is deliberate and the
     * reason {@code automation} is only invoked for an owned lead: a customer
     * who messaged us at 2am must not have their enquiry discarded because the
     * office is unstaffed. An unowned lead sits in the list until a manager
     * assigns it, which is visible and recoverable; losing the enquiry is not.
     */
    private UUID pickInboundOwner() {
        try {
            User owner = roundRobin.pickNextSalesUser();
            return owner == null ? null : owner.getId();
        } catch (RuntimeException e) {
            log.warn("[lead] no owner available for an inbound lead; leaving it unassigned: {}", e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Page<LeadResponse> list(UUID ownerId, Lead.Status status, Lead.Source source, Lead.Heat heat,
                                   UUID tripId, Instant from, Instant to, LocalDate travelFrom,
                                   LocalDate travelTo, String search, int page, int size, UserPrincipal caller) {
        UUID effectiveOwner = SEES_ALL.contains(caller.role()) ? ownerId : caller.id();
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<Lead> result = leads.search(effectiveOwner,
                status == null ? null : status.name(),
                source == null ? null : source.name(),
                heat == null ? null : heat.name(),
                tripId,
                from, to, travelFrom, travelTo,
                emptyToNull(search), pageable);
        return new PageImpl<>(result.getContent().stream().map(this::toResponse).toList(),
                pageable, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public LeadResponse get(UUID id, UserPrincipal caller) {
        Lead lead = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead not found: " + id));
        assertCanAccess(lead, caller);
        return toResponse(lead);
    }

    @Transactional
    public LeadResponse update(UUID id, LeadUpdateRequest request, UserPrincipal caller) {
        requireRole(caller.role(), Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);

        Lead lead = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead not found: " + id));
        assertCanAccess(lead, caller);

        auditChanged(lead, "customerName", lead.getCustomerName(), request.customerName(), v -> lead.setCustomerName(v));
        auditChanged(lead, "email", lead.getEmail(), request.email(), v -> lead.setEmail(v));
        auditChanged(lead, "whatsappNumber", lead.getWhatsappNumber(), request.whatsappNumber(), v -> lead.setWhatsappNumber(v));
        auditChanged(lead, "destination", lead.getDestination(), request.destination(), v -> lead.setDestination(v));
        auditChanged(lead, "tripId", toStr(lead.getTripId()), toStr(request.tripId()), v -> lead.setTripId(v == null ? null : UUID.fromString(v)));
        auditChanged(lead, "travelDate", toStr(lead.getTravelDate()), toStr(request.travelDate()), v -> lead.setTravelDate(v == null ? null : LocalDate.parse(v)));
        auditChanged(lead, "numPersons", String.valueOf(lead.getNumPersons()), toStr(request.numPersons()), v -> lead.setNumPersons(Integer.parseInt(v)));
        auditChanged(lead, "budget", toStr(lead.getBudget()), toStr(request.budget()), v -> lead.setBudget(v == null ? null : new BigDecimal(v)));
        auditChanged(lead, "followUpDate", toStr(lead.getFollowUpDate()), toStr(request.followUpDate()), v -> lead.setFollowUpDate(v == null ? null : LocalDate.parse(v)));
        auditChanged(lead, "remarks", lead.getRemarks(), request.remarks(), v -> lead.setRemarks(v));

        // Rule-based scoring recomputes whenever enquiry fields change.
        lead.setHeat(scoringService.score(lead));
        leads.save(lead);
        return toResponse(lead);
    }

    @Transactional(readOnly = true)
    public List<LeadActivityResponse> activity(UUID id, UserPrincipal caller) {
        Lead lead = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead not found: " + id));
        assertCanAccess(lead, caller);
        return auditLogRepository.findAllByEntityAndEntityIdOrderBySeqDesc("LEAD", id).stream()
                .map(entry -> new LeadActivityResponse(
                        entry.getSeq(), entry.getAction(), entry.getField(), entry.getOldValue(),
                        entry.getNewValue(), actorName(entry.getActorId()), entry.getCreatedAt()))
                .toList();
    }

    private void auditChanged(Lead lead, String field, String oldValue, String newValue,
                              java.util.function.Consumer<String> apply) {
        if (newValue == null || newValue.equals(oldValue)) return;
        apply.accept(newValue);
        auditService.record("LEAD", lead.getId(), com.securetravels.crm.common.audit.AuditAction.UPDATE,
                field, oldValue, newValue);
    }

    private String actorName(UUID actorId) {
        if (actorId == null) return null;
        return users.findById(actorId).map(User::getFullName).orElse(null);
    }

    private static String toStr(Object value) { return value == null ? null : value.toString(); }

    @Transactional
    public LeadResponse updateStatus(UUID id, LeadStatusRequest request, UserPrincipal caller) {
        requireRole(caller.role(), Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);

        Lead lead = leads.findById(id).orElseThrow(() -> new NotFoundException("Lead not found: " + id));
        assertCanAccess(lead, caller);

        Lead.Status current = lead.getStatus();
        Set<Lead.Status> allowed = TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(request.status())) {
            throw new BadRequestException("Invalid status transition: " + current + " -> " + request.status());
        }
        if (request.status() == Lead.Status.LOST && request.lostReason() == null) {
            throw new BadRequestException("lostReason is required when marking a lead as LOST");
        }

        String oldStatus = current.name();
        String oldReason = lead.getLostReason() == null ? null : lead.getLostReason().name();
        if (request.status() == Lead.Status.LOST) {
            lead.markLost(request.lostReason());
        } else {
            lead.setStatus(request.status());
        }

        leads.save(lead);

        if (request.status() == Lead.Status.LOST) {
            if (request.lostReason() != null && !request.lostReason().name().equals(oldReason)) {
                auditService.statusChange("LEAD", lead.getId(), "lost_reason", oldReason, request.lostReason().name());
            }
        } else {
            auditService.statusChange("LEAD", lead.getId(), "status", oldStatus, request.status().name());
        }
        if (request.note() != null && !request.note().isBlank()) {
            auditService.record("LEAD", lead.getId(), com.securetravels.crm.common.audit.AuditAction.UPDATE,
                    "note", null, request.note().trim());
        }
        if (request.status() == Lead.Status.INTERESTED) {
            automation.onInterested(lead);
        } else if (request.status() == Lead.Status.QUOTATION_SENT) {
            automation.onQuotationSent(lead);
        } else if (request.status() == Lead.Status.BOOKING_CONFIRMED) {
            automation.onBooked(lead);
        }
        return toResponse(lead);
    }

    // ------------------------------------------------------------------ helpers

    private void assertCanAccess(Lead lead, UserPrincipal caller) {
        if (!SEES_ALL.contains(caller.role()) && !caller.id().equals(lead.getOwnerId())) {
            throw new ForbiddenException("You can only manage leads assigned to you");
        }
    }

    private static void requireRole(Role actual, Role... allowed) {
        for (Role r : allowed) {
            if (r == actual) return;
        }
        throw new ForbiddenException("This role (" + actual + ") cannot perform this action");
    }

    private String normalizedDigits(String mobile) {
        String digits = PhoneUtils.normalize(mobile);
        if (digits == null) {
            throw new BadRequestException("mobileNumber is not a valid Indian mobile number");
        }
        return digits;
    }

    private LeadResponse toResponse(Lead lead) {
        String ownerName = lead.getOwnerId() == null ? null
                : users.findById(lead.getOwnerId()).map(User::getFullName).orElse(null);
        return new LeadResponse(
                lead.getId(), lead.getCustomerName(), lead.getMobileNumber(), lead.getWhatsappNumber(),
                lead.getEmail(), lead.getSource(), lead.getDestination(), lead.getTripId(),
                lead.getTravelDate(), lead.getNumPersons(), lead.getBudget(), lead.getOwnerId(), ownerName,
                lead.getCustomer360Id(),
                lead.getStatus(), lead.getHeat(), lead.getFollowUpDate(), lead.getRemarks(),
                lead.isConsentGiven(), lead.getConsentScope(), lead.getConsentCapturedAt(),
                lead.getLostReason(), lead.getDuplicateOfLeadId(), lead.getLastContactedAt(),
                lead.getCreatedAt(), lead.getUpdatedAt());
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}