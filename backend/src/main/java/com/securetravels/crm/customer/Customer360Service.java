package com.securetravels.crm.customer;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.customer.dto.CustomerDetailResponse;
import com.securetravels.crm.customer.dto.CustomerListResponse;
import com.securetravels.crm.customer.dto.CustomerUpdateRequest;
import com.securetravels.crm.payment.Payment;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Customer Database (Module 13). Customer360 is the canonical person record;
 * this surface exposes a customer directory plus a full trip-history timeline
 * and the marketing/remarketing flags (suggest-offer, tags, opt-in). Sales
 * can read; MANAGER/ADMIN/CEO can maintain the flags and notes.
 */
@Service
public class Customer360Service {

    private static final Logger log = LoggerFactory.getLogger(Customer360Service.class);
    private static final Set<Role> WRITERS = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO);
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "Kashmir", "Char Dham", "Family tour", "Anniversary", "Honeymoon",
            "Kedarnath", "Adventure", "Group", "Seniors", "High value");
    private static final List<Booking.Status> TRIP_STATUSES =
            List.of(Booking.Status.CONFIRMED, Booking.Status.COMPLETED);
    private static final List<Payment.Status> APPLIED_STATUSES =
            List.of(Payment.Status.COMPLETED, Payment.Status.PARTIAL);

    private final Customer360Repository customers;
    private final BookingRepository bookings;
    private final AuditService auditService;
    private final ConsentService consentService;

    public Customer360Service(Customer360Repository customers, BookingRepository bookings,
                              AuditService auditService, ConsentService consentService) {
        this.customers = customers;
        this.bookings = bookings;
        this.auditService = auditService;
        this.consentService = consentService;
    }

    @Transactional(readOnly = true)
    public List<CustomerListResponse> list(String search, UserPrincipal caller) {
        String term = search == null || search.isBlank() ? null : XssSanitizer.text(search.trim());
        return customers.search(term).stream()
                .map(this::toListResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse get(UUID id, UserPrincipal caller) {
        Customer360 c = getCustomer(id);
        List<CustomerDetailResponse.TripRow> history = bookings.tripHistory(id, APPLIED_STATUSES, TRIP_STATUSES).stream()
                .map(this::toTripRow)
                .toList();
        return new CustomerDetailResponse(
                c.getId(), c.getFullName(), c.getMobileNumber(), c.getWhatsappNumber(), c.getEmail(),
                c.isConsentGiven(), c.getConsentCapturedAt(), c.getConsentScope(),
                c.isMarketingOptIn(), history.size(), bookings.lastTripDate(id), totalSpent(history),
                c.getSuggestOffer(), c.offerTagsCopy(), c.getNotes(), history,
                consentService.marketingStatus(id), c.getCreatedAt(), c.getUpdatedAt());
    }

    @Transactional
    public CustomerDetailResponse update(UUID id, CustomerUpdateRequest request, UserPrincipal caller) {
        requireWriter(caller.role());
        Customer360 c = getCustomer(id);

        if (request.suggestOffer() != null) {
            String sanitized = XssSanitizer.text(request.suggestOffer().trim());
            if (sanitized.isBlank()) {
                throw new BadRequestException("suggestOffer cannot be blank when provided");
            }
            String old = c.getSuggestOffer();
            c.setSuggestOffer(sanitized);
            auditService.record("CUSTOMER360", c.getId(), AuditAction.UPDATE, "suggest_offer", old, sanitized);
        }

        if (request.offerTags() != null) {
            List<String> incoming = Arrays.stream(request.offerTags())
                    .map(t -> XssSanitizer.text(t == null ? "" : t.trim()))
                    .filter(t -> !t.isEmpty())
                    .distinct()
                    .toList();
            for (String t : incoming) {
                if (!ALLOWED_TAGS.contains(t)) {
                    throw new BadRequestException("Unsupported offer tag: " + t + " (allowed: " + ALLOWED_TAGS + ")");
                }
            }
            String[] old = c.offerTagsCopy();
            c.setOfferTags(incoming.toArray(new String[0]));
            auditService.record("CUSTOMER360", c.getId(), AuditAction.UPDATE, "offer_tags",
                    String.join(",", old), String.join(",", incoming));
        }

        if (request.marketingOptIn() != null) {
            boolean old = c.isMarketingOptIn();
            if (old != request.marketingOptIn()) {
                c.setMarketingOptIn(request.marketingOptIn());
                auditService.record("CUSTOMER360", c.getId(), AuditAction.UPDATE, "marketing_opt_in",
                        String.valueOf(old), String.valueOf(request.marketingOptIn()));
                consentService.syncMarketingOptIn(c.getId(), request.marketingOptIn(), caller.id());
            }
        }

        if (request.notes() != null) {
            if (request.notes().isBlank()) {
                throw new BadRequestException("notes cannot be blank when provided");
            }
            String old = c.getNotes();
            String sanitized = XssSanitizer.text(request.notes().trim());
            c.setNotes(sanitized);
            auditService.record("CUSTOMER360", c.getId(), AuditAction.UPDATE, "notes", old, sanitized);
        }

        customers.save(c);
        log.info("[customers] updated {} by {}", c.getId(), caller.id());
        return get(c.getId(), caller);
    }

    // ------------------------------------------------------------------ aggregates

    /**
     * Recomputes the denormalised customer aggregates (total_trips,
     * last_trip_date, total_spent) from live bookings/payments. Called after
     * booking status changes and payment transitions.
     */
    @Transactional
    public void maintainAggregates(UUID customerId) {
        Customer360 c = getCustomer(customerId);
        List<CustomerDetailResponse.TripRow> history = bookings.tripHistory(customerId, APPLIED_STATUSES, TRIP_STATUSES).stream()
                .map(this::toTripRow)
                .toList();
        int trips = history.size();
        BigDecimal spent = totalSpent(history);
        boolean changed = trips != c.getTotalTrips()
                || spent.compareTo(c.getTotalSpent() == null ? BigDecimal.ZERO : c.getTotalSpent()) != 0
                || !java.util.Objects.equals(c.getLastTripDate(), bookings.lastTripDate(customerId));
        if (!changed) {
            return;
        }
        c.setTotalTrips(trips);
        c.setTotalSpent(spent);
        c.setLastTripDate(bookings.lastTripDate(customerId));
        customers.save(c);
    }

    private CustomerListResponse toListResponse(Customer360 c) {
        return new CustomerListResponse(
                c.getId(), c.getFullName(), c.getMobileNumber(), c.getEmail(),
                c.isMarketingOptIn(), c.isConsentGiven(), c.getTotalTrips(), c.getLastTripDate(),
                c.getTotalSpent(), c.getSuggestOffer(), c.offerTagsCopy(), c.getNotes(), c.getCreatedAt());
    }

    private CustomerDetailResponse.TripRow toTripRow(BookingRepository.TripHistoryRow row) {
        return new CustomerDetailResponse.TripRow(
                row.getBookingId(), row.getBookingRef(), row.getTripId(), row.getTripName(),
                row.getDepartureDate(), row.getStatus(), row.getNetAmount(), row.getAppliedAmount());
    }

    private BigDecimal totalSpent(List<CustomerDetailResponse.TripRow> history) {
        return history.stream()
                .map(CustomerDetailResponse.TripRow::appliedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Customer360 getCustomer(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + id));
    }

    private static void requireWriter(Role role) {
        if (!WRITERS.contains(role)) {
            throw new ForbiddenException("This role (" + role + ") cannot maintain customer flags");
        }
    }
}
