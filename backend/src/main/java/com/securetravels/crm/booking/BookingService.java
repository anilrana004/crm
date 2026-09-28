package com.securetravels.crm.booking;

import com.securetravels.crm.booking.dto.BookingCreateRequest;
import com.securetravels.crm.booking.dto.BookingResponse;
import com.securetravels.crm.booking.dto.BookingStatusRequest;
import com.securetravels.crm.booking.dto.TravellerInput;
import com.securetravels.crm.booking.dto.TravellerResponse;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.commission.CommissionLedgerService;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.customer.Customer360Service;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.lead.LeadService;
import com.securetravels.crm.lead.dto.LeadStatusRequest;
import com.securetravels.crm.payment.PaymentService;
import com.securetravels.crm.operations.OperationsService;
import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.BookingType;
import com.securetravels.crm.trip.CapacityAlertService;
import com.securetravels.crm.trip.SeatHold;
import com.securetravels.crm.trip.SeatHoldRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 4 — bookings.
 *
 *   • FIXED_BATCH: seats are consumed via a 2-hour provisional {@link SeatHold}
 *     created inside a PESSIMISTIC_WRITE lock on the batch row (I3). The hold
 *     counts against seats the moment it is taken, is confirmed with the
 *     booking, and is released/reverted on cancellation or by the sweep.
 *   • CUSTOM_FIT: no batch, no shared seat pool; date is customer-chosen.
 *   • Confirming a booking advances its originating lead to
 *     BOOKING_CONFIRMED; cancelling a confirmed booking reopens the lead.
 *   • A discount above the approval threshold (DiscountPolicy, default 5% of
 *     gross) can only be confirmed by a manager; at or below it is
 *     auto-approved by the confirming writer.
 */
@Service
public class BookingService {

    private static final Set<Role> SEES_ALL = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO, Role.OPS);

    private static final int HOLD_HOURS = 2;

    private final BookingRepository bookings;
    private final TravellerRepository travellers;
    private final SeatHoldRepository seatHolds;
    private final BatchRepository batches;
    private final TripRepository trips;
    private final Customer360Repository customers;
    private final LeadRepository leads;
    private final UserRepository users;
    private final AuditService auditService;
    private final LeadService leadService;
    private final PaymentService paymentService;
    private final OperationsService operationsService;
    private final Customer360Service customerService;
    private final DiscountPolicy discountPolicy;
    private final CapacityAlertService capacityAlerts;
    private final CommissionLedgerService commissionLedger;
    private final org.springframework.context.ApplicationEventPublisher events;

    public BookingService(BookingRepository bookings, TravellerRepository travellers,
                          SeatHoldRepository seatHolds, BatchRepository batches, TripRepository trips,
                          Customer360Repository customers, LeadRepository leads, UserRepository users,
                          AuditService auditService, LeadService leadService, PaymentService paymentService,
                          OperationsService operationsService, Customer360Service customerService,
                          DiscountPolicy discountPolicy, CapacityAlertService capacityAlerts,
                          CommissionLedgerService commissionLedger,
                      org.springframework.context.ApplicationEventPublisher events) {
        this.bookings = bookings;
        this.travellers = travellers;
        this.seatHolds = seatHolds;
        this.batches = batches;
        this.trips = trips;
        this.customers = customers;
        this.leads = leads;
        this.users = users;
        this.auditService = auditService;
        this.leadService = leadService;
        this.paymentService = paymentService;
        this.operationsService = operationsService;
        this.customerService = customerService;
        this.discountPolicy = discountPolicy;
        this.capacityAlerts = capacityAlerts;
        this.commissionLedger = commissionLedger;
        this.events = events;
    }

    @Transactional
    public BookingResponse create(BookingCreateRequest request, UserPrincipal caller) {
        requireRole(caller.role(), Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);
        if (request.customerId() == null && request.leadId() == null) {
            throw new BadRequestException("customerId or leadId is required");
        }

        UUID customerId = resolveCustomer(request, caller);
        Trip trip = trips.findById(request.tripId())
                .orElseThrow(() -> new NotFoundException("Trip not found: " + request.tripId()));
        BookingType type = BookingType.valueOf(trip.getBookingType().name());

        int numTravellers = request.numTravellers() != null
                ? request.numTravellers()
                : (request.travellers() == null ? 1 : request.travellers().size());
        if (request.travellers() != null && !request.travellers().isEmpty()
                && request.travellers().size() != numTravellers) {
            throw new BadRequestException("travellers count (" + request.travellers().size()
                    + ") must match numTravellers (" + numTravellers + ")");
        }

        BigDecimal unit = trip.getBaseCost() == null ? BigDecimal.ZERO : trip.getBaseCost();
        BigDecimal total = unit.multiply(BigDecimal.valueOf(numTravellers));
        BigDecimal discount = request.discountAmount() == null ? BigDecimal.ZERO : request.discountAmount();
        if (discount.compareTo(total) > 0) {
            throw new BadRequestException("discountAmount cannot exceed the gross total (" + total + ")");
        }

        Booking booking = new Booking();
        booking.setBookingRef(nextRef());
        booking.setTripId(trip.getId());
        booking.setBookingType(type);
        booking.setCustomerId(customerId);
        booking.setNumTravellers(numTravellers);
        booking.setTotalAmount(total);
        booking.setDiscountAmount(discount);
        booking.setTaxAmount(BigDecimal.ZERO);
        booking.setNotes(XssSanitizer.text(request.notes()));
        booking.setCreatedBy(caller.id());
        booking.setLeadId(request.leadId());

        if (type == BookingType.FIXED_BATCH) {
            booking = createFixedBatchBooking(request, booking, trip, numTravellers);
        } else {
            if (request.batchId() != null) {
                throw new BadRequestException("CUSTOM_FIT bookings must not have a batch");
            }
            if (request.travelDate() == null) {
                throw new BadRequestException("travelDate is required for CUSTOM_FIT bookings");
            }
            booking.setBatchId(null);
            booking.setTravelDate(request.travelDate());
            booking = bookings.save(booking);
        }

        if (request.travellers() != null) {
            for (TravellerInput t : request.travellers()) {
                Traveller tr = new Traveller(booking.getId(), XssSanitizer.text(t.fullName()));
                tr.setAge(t.age());
                tr.setGender(XssSanitizer.text(t.gender()));
                tr.setPhone(XssSanitizer.text(t.phone()));
                tr.setMedicalCertRequired(t.medicalCertFlag());
                travellers.save(tr);
            }
        }

        auditService.record("BOOKING", booking.getId(), AuditAction.CREATE, "booking_ref", null, booking.getBookingRef());
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list(UUID tripId, UUID batchId, UUID customerId, Booking.Status status,
                                      UserPrincipal caller) {
        UUID effectiveCreatedBy = SEES_ALL.contains(caller.role()) ? null : caller.id();
        return bookings.search(status, tripId, batchId, customerId, effectiveCreatedBy).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID id, UserPrincipal caller) {
        Booking booking = getBooking(id);
        assertCanRead(booking, caller);
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateStatus(UUID id, BookingStatusRequest request, UserPrincipal caller) {
        requireRole(caller.role(), Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);

        Booking booking = getBooking(id);
        assertCanWrite(booking, caller);

        switch (request.status()) {
            case CONFIRMED -> confirm(booking, request.note(), caller);
            case CANCELLED -> cancel(booking, caller);
            case COMPLETED -> complete(booking);
            default -> throw new BadRequestException("QUOTATION is the initial booking state; use create rather than this endpoint");
        }
        return toResponse(booking);
    }

    // ------------------------------------------------------------------ status transitions

    private void confirm(Booking booking, String note, UserPrincipal caller) {
        if (booking.getStatus() != Booking.Status.QUOTATION) {
            throw new ConflictException("Booking must be in QUOTATION to confirm (now " + booking.getStatus() + ")");
        }
        if (booking.getBookingType() == BookingType.FIXED_BATCH) {
            confirmHold(booking);
        }
        if (booking.getDiscountAmount() != null && booking.getDiscountAmount().signum() > 0) {
            boolean needsManager = discountPolicy.requiresManagerApproval(booking.getDiscountAmount(),
                    booking.getTotalAmount());
            if (needsManager && caller.role() != Role.MANAGER && caller.role() != Role.ADMIN
                    && caller.role() != Role.CEO) {
                throw new ForbiddenException("This discount exceeds the approval limit — a manager must confirm it");
            }
            booking.setDiscountApprovedBy(caller.id());
        }

        String oldStatus = booking.getStatus().name();
        booking.setStatus(Booking.Status.CONFIRMED);
        auditService.statusChange("BOOKING", booking.getId(), "status", oldStatus, "CONFIRMED");
        if (note != null && !note.isBlank()) {
            auditService.record("BOOKING", booking.getId(), AuditAction.UPDATE, "note", null, XssSanitizer.text(note.trim()));
        }
        if (booking.getLeadId() != null) {
            Lead lead = leads.findById(booking.getLeadId()).orElse(null);
            if (lead != null) {
                if (lead.getStatus() == Lead.Status.QUOTATION_SENT) {
                    leadService.updateStatus(lead.getId(),
                            new LeadStatusRequest(Lead.Status.BOOKING_CONFIRMED, null,
                                    note == null ? null : XssSanitizer.text(note.trim())), caller);
                }
                // Snapshot revenue attribution to the lead's owner while the booking
                // is confirmed, in the same transaction. Deriving it later from
                // leads.owner_id would let a later reassignment rewrite this
                // booking's history; see V13 for the full argument.
                commissionLedger.credit(booking, lead, Instant.now());
            }
        }
        operationsService.onBookingConfirmed(booking, caller);
        customerService.maintainAggregates(booking.getCustomerId());
        publishBookingConfirmed(booking);
    }

    /**
     * Queue the customer's confirmation message (Module 4).
     *
     * <p>Everything is resolved here, inside the transaction, and published as a
     * single event. The actual send happens in an AFTER_COMMIT listener, so a
     * rollback cannot produce a "you are booked" message for a booking that does
     * not exist, and a WhatsApp failure cannot fail the confirmation.
     */
    private void publishBookingConfirmed(Booking booking) {
        customers.findById(booking.getCustomerId()).ifPresent(customer -> {
            String mobile = customer.getWhatsappNumber() != null && !customer.getWhatsappNumber().isBlank()
                    ? customer.getWhatsappNumber() : customer.getMobileNumber();
            if (mobile == null || mobile.isBlank()) {
                return;   // no reachable number; nothing to send and nobody to ask
            }
            String packageName = trips.findById(booking.getTripId())
                    .map(trip -> trip.getName())
                    .orElse("your trip");
            events.publishEvent(new BookingConfirmedEvent(
                    booking.getId(), customer.getId(), booking.getBookingRef(),
                    customer.getFullName(), mobile, packageName, booking.getTravelDate()));
        });
    }

    private void confirmHold(Booking booking) {
        SeatHold hold = seatHolds.findByBookingIdAndStatus(booking.getId(), SeatHold.Status.HELD)
                .stream().findFirst()
                .orElseThrow(() -> new ConflictException("No active (HELD) seat hold for this booking — "
                        + "the 2-hour hold may have expired, so the booking can no longer be confirmed"));
        if (hold.getHeldUntil().isBefore(Instant.now())) {
            throw new ConflictException("Seat hold expired at " + hold.getHeldUntil()
                    + " — recreate the booking to take fresh seats");
        }
        hold.confirm(booking.getId());
        seatHolds.save(hold);
    }

    private void cancel(Booking booking, UserPrincipal caller) {
        if (booking.getStatus() == Booking.Status.CANCELLED) {
            return; // idempotent
        }
        if (booking.getStatus() != Booking.Status.QUOTATION && booking.getStatus() != Booking.Status.CONFIRMED) {
            throw new BadRequestException("Cannot cancel a " + booking.getStatus() + " booking");
        }
        if (booking.getBookingType() == BookingType.FIXED_BATCH) {
            releaseSeats(booking);
        }

        String oldStatus = booking.getStatus().name();
        booking.setStatus(Booking.Status.CANCELLED);
        auditService.statusChange("BOOKING", booking.getId(), "status", oldStatus, "CANCELLED");
        paymentService.autoCancelForBooking(booking.getId(), "Booking " + booking.getBookingRef() + " cancelled");
        operationsService.onBookingCancelled(booking);
        customerService.maintainAggregates(booking.getCustomerId());

        if (oldStatus.equals(Booking.Status.CONFIRMED.name()) && booking.getLeadId() != null) {
            leads.findById(booking.getLeadId()).ifPresent(lead -> {
                if (lead.getStatus() == Lead.Status.BOOKING_CONFIRMED) {
                    leadService.updateStatus(lead.getId(),
                            new LeadStatusRequest(Lead.Status.QUOTATION_SENT, null,
                                    "Booking " + booking.getBookingRef() + " cancelled; lead reopened"), caller);
                }
            });
        }

        // Withdraw, never delete. A commission dispute is settled by showing when
        // the credit appeared and when it was withdrawn, so the trail has to
        // outlive the reversal. Only a CONFIRMED booking was ever credited, so
        // this is a no-op for the rest.
        if (oldStatus.equals(Booking.Status.CONFIRMED.name())) {
            commissionLedger.revoke(booking.getId(),
                    "Booking " + booking.getBookingRef() + " cancelled");
        }
    }

    private void releaseSeats(Booking booking) {
        Batch batch = batches.findWithLockById(booking.getBatchId())
                .orElseThrow(() -> new NotFoundException("Batch not found: " + booking.getBatchId()));
        boolean wasFull = batch.seatsAvailable() == 0;

        List<SeatHold> active = seatHolds.findByBookingIdAndStatusIn(booking.getId(),
                List.of(SeatHold.Status.HELD, SeatHold.Status.CONFIRMED));
        int released = active.stream().mapToInt(SeatHold::getNumSeats).sum();
        active.forEach(h -> h.mark(SeatHold.Status.RELEASED));
        seatHolds.saveAll(active);

        if (released > 0) {
            batch.setSeatsBooked(Math.max(0, batch.getSeatsBooked() - released));
        }
        if (wasFull && batch.getStatus() == Batch.Status.CLOSED) {
            batch.setStatus(Batch.Status.OPEN);
            auditService.statusChange("BATCH", batch.getId(), "status", "CLOSED", "OPEN");
        }
        batches.save(batch);
    }

    private void complete(Booking booking) {
        if (booking.getStatus() == Booking.Status.COMPLETED) {
            return; // idempotent
        }
        if (booking.getStatus() != Booking.Status.CONFIRMED) {
            throw new BadRequestException("Only CONFIRMED bookings can be completed (now " + booking.getStatus() + ")");
        }
        booking.setStatus(Booking.Status.COMPLETED);
        auditService.statusChange("BOOKING", booking.getId(), "status", "CONFIRMED", "COMPLETED");
        customerService.maintainAggregates(booking.getCustomerId());
    }

    // ------------------------------------------------------------------ FIXED_BATCH seat pool

    private Booking createFixedBatchBooking(BookingCreateRequest request, Booking booking, Trip trip, int numTravellers) {
        if (request.batchId() == null) {
            throw new BadRequestException("FIXED_BATCH bookings require a batch");
        }
        Batch batch = batches.findWithLockById(request.batchId())
                .orElseThrow(() -> new NotFoundException("Batch not found: " + request.batchId()));
        if (!batch.getTripId().equals(trip.getId())) {
            throw new BadRequestException("Batch does not belong to this trip");
        }
        if (batch.getStatus() == Batch.Status.CANCELLED) {
            throw new BadRequestException("Cannot book a cancelled batch");
        }
        if (batch.getStatus() == Batch.Status.CLOSED || batch.seatsAvailable() < numTravellers) {
            throw new BadRequestException("Only " + batch.seatsAvailable()
                    + " seats are available on this batch (requested " + numTravellers + ")");
        }

        booking.setBatchId(batch.getId());
        booking.setTravelDate(batch.getDepartureDate());
        booking = bookings.save(booking);

        SeatHold hold = new SeatHold(batch.getId(), numTravellers, Instant.now().plus(HOLD_HOURS, ChronoUnit.HOURS));
        hold.assignToBooking(booking.getId());
        seatHolds.save(hold);

        batch.setSeatsBooked(batch.getSeatsBooked() + numTravellers);
        // Module 3: judge the scarcity crossing here, while the PESSIMISTIC_WRITE
        // lock on this batch row is still held, so the alert is raised at most
        // once even if two bookings for the last seats race each other.
        capacityAlerts.onSeatsBooked(batch, trips.findById(batch.getTripId()).orElse(null));
        if (batch.getStatus() == Batch.Status.OPEN && batch.seatsAvailable() == 0) {
            batch.setStatus(Batch.Status.CLOSED);
            auditService.statusChange("BATCH", batch.getId(), "status", "OPEN", "CLOSED");
        }
        batches.save(batch);
        return booking;
    }

    // ------------------------------------------------------------------ customer & refs

    private UUID resolveCustomer(BookingCreateRequest request, UserPrincipal caller) {
        if (request.customerId() != null) {
            if (!customers.existsById(request.customerId())) {
                throw new BadRequestException("Customer not found: " + request.customerId());
            }
            return request.customerId();
        }
        Lead lead = leads.findById(request.leadId())
                .orElseThrow(() -> new NotFoundException("Lead not found: " + request.leadId()));
        if (lead.getCustomer360Id() != null) {
            return lead.getCustomer360Id();
        }
        if (lead.getMobileDigits() == null || lead.getMobileDigits().isBlank()) {
            throw new BadRequestException("Lead has no mobile number — cannot build a Customer360; pass customerId");
        }
        Customer360 customer = Customer360.fromLead(
                lead.getCustomerName(), lead.getMobileNumber(), lead.getMobileDigits(),
                lead.getWhatsappNumber(), lead.getEmail(),
                lead.isConsentGiven(), lead.getConsentScope());
        customer = customers.save(customer);
        lead.setCustomer360Id(customer.getId());
        leads.save(lead);
        auditService.record("CUSTOMER360", customer.getId(), AuditAction.CREATE, "lead_id", null, lead.getId().toString());
        return customer.getId();
    }

    private String nextRef() {
        int year = LocalDate.now().getYear();
        String prefix = "TOH-" + year + "-";
        return prefix + String.format("%04d", bookings.countByBookingRefStartingWith(prefix) + 1);
    }

    // ------------------------------------------------------------------ response mapping

    private BookingResponse toResponse(Booking booking) {
        String tripName = trips.findById(booking.getTripId()).map(Trip::getName).orElse(null);
        LocalDate departureDate = booking.getBatchId() == null ? null
                : batches.findById(booking.getBatchId()).map(Batch::getDepartureDate).orElse(null);
        Customer360 customer = customers.findById(booking.getCustomerId()).orElse(null);
        String createdByName = booking.getCreatedBy() == null ? null
                : users.findById(booking.getCreatedBy()).map(User::getFullName).orElse(null);
        List<TravellerResponse> travellerResponses = travellers.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .map(t -> new TravellerResponse(t.getId(), t.getFullName(), t.getAge(), t.getGender(),
                        t.getPhone(), t.isMedicalCertRequired()))
                .toList();
        return new BookingResponse(
                booking.getId(), booking.getBookingRef(), booking.getTripId(), tripName,
                booking.getBatchId(), departureDate, booking.getCustomerId(),
                customer == null ? null : customer.getFullName(),
                customer == null ? null : customer.getMobileNumber(),
                booking.getBookingType(), booking.getNumTravellers(), booking.getTotalAmount(),
                booking.getDiscountAmount(), booking.getTaxAmount(), booking.netAmount(),
                booking.getStatus(), booking.getTravelDate(), booking.getDiscountApprovedBy(),
                booking.getNotes(), booking.getLeadId(), booking.getCreatedBy(), createdByName,
                travellerResponses, booking.getCreatedAt(), booking.getUpdatedAt());
    }

    private Booking getBooking(UUID id) {
        return bookings.findById(id).orElseThrow(() -> new NotFoundException("Booking not found: " + id));
    }

    private void assertCanRead(Booking booking, UserPrincipal caller) {
        if (SEES_ALL.contains(caller.role())) return;
        if (caller.role() == Role.SALES && caller.id().equals(booking.getCreatedBy())) return;
        throw new ForbiddenException("You can only view bookings you created");
    }

    private void assertCanWrite(Booking booking, UserPrincipal caller) {
        if (SEES_ALL.contains(caller.role())) return;
        if (caller.role() == Role.SALES && caller.id().equals(booking.getCreatedBy())) return;
        throw new ForbiddenException("You can only manage bookings you created");
    }

    private static void requireRole(Role actual, Role... allowed) {
        for (Role r : allowed) {
            if (r == actual) return;
        }
        throw new ForbiddenException("This role (" + actual + ") cannot perform this action");
    }
}