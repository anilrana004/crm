package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.document.ComplianceService;
import com.securetravels.crm.trip.dto.BatchGenerateRequest;
import com.securetravels.crm.trip.dto.BatchGenerateResponse;
import com.securetravels.crm.trip.dto.BatchRecurrence;
import com.securetravels.crm.trip.dto.BatchResponse;
import com.securetravels.crm.trip.dto.TripCreateRequest;
import com.securetravels.crm.trip.dto.TripDetailResponse;
import com.securetravels.crm.trip.dto.TripUpdateRequest;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.vendors.Vendor;
import com.securetravels.crm.vendors.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    private final TripRepository trips;
    private final BatchRepository batches;
    private final VendorRepository vendors;
    private final AuditService auditService;
    private final ComplianceService complianceService;
    private final AppProperties props;

    public TripService(TripRepository trips, BatchRepository batches, VendorRepository vendors,
                       AuditService auditService, ComplianceService complianceService,
                       AppProperties props) {
        this.trips = trips;
        this.batches = batches;
        this.vendors = vendors;
        this.auditService = auditService;
        this.complianceService = complianceService;
        this.props = props;
    }

    @Transactional
    public TripDetailResponse create(TripCreateRequest request, UserPrincipal caller) {
        requireManager(caller);

        Trip trip = new Trip();
        trip.setName(request.name());
        trip.setSlug(nextAvailableSlug(resolveSlug(request.name(), request.slug())));
        trip.setCategory(request.category());
        trip.setBookingType(request.bookingType());
        trip.setDifficulty(request.difficulty());
        trip.setBaseCost(request.baseCost());
        trip.setDurationDays(request.durationDays());
        trip.setItinerary(XssSanitizer.text(request.itinerary()));
        trip.setInclusions(XssSanitizer.text(request.inclusions()));
        trip.setExclusions(XssSanitizer.text(request.exclusions()));
        trip.setActive(true);

        Trip saved = trips.save(trip);
        auditService.record("TRIP", saved.getId(), AuditAction.CREATE, "name", null, saved.getName());
        return toDetail(saved);
    }

    @Transactional(readOnly = true)
    public List<Trip> list(boolean active) {
        return active
                ? trips.findAllByActiveTrueOrderByNameAsc()
                : trips.findAllByActiveFalseOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public TripDetailResponse get(UUID id) {
        return toDetail(getTrip(id));
    }

    @Transactional
    public TripDetailResponse update(UUID id, TripUpdateRequest request, UserPrincipal caller) {
        requireManager(caller);

        Trip trip = getTrip(id);
        auditChange(trip, "name", trip.getName(), request.name(), trip::setName);
        if (request.slug() != null && !request.slug().isBlank() && !request.slug().equals(trip.getSlug())) {
            String next = nextAvailableSlug(request.slug());
            auditChange(trip, "slug", trip.getSlug(), next, trip::setSlug);
        }
        if (request.category() != null) {
            auditChange(trip, "category", trip.getCategory().name(), request.category().name(),
                    v -> trip.setCategory(Trip.Category.valueOf(v)));
        }
        if (request.bookingType() != null) {
            auditChange(trip, "bookingType", trip.getBookingType().name(), request.bookingType().name(),
                    v -> trip.setBookingType(Trip.BookingType.valueOf(v)));
        }
        if (request.difficulty() != null) {
            auditChange(trip, "difficulty", trip.getDifficulty() == null ? null : trip.getDifficulty().name(),
                    request.difficulty().name(), v -> trip.setDifficulty(Trip.Difficulty.valueOf(v)));
        }
        auditChange(trip, "baseCost", trip.getBaseCost() == null ? null : trip.getBaseCost().toString(),
                request.baseCost() == null ? null : request.baseCost().toString(),
                v -> trip.setBaseCost(new BigDecimal(v)));
        auditChange(trip, "durationDays", String.valueOf(trip.getDurationDays()),
                request.durationDays() == null ? null : String.valueOf(request.durationDays()),
                v -> trip.setDurationDays(Integer.parseInt(v)));
        if (request.itinerary() != null) {
            auditChange(trip, "itinerary", trip.getItinerary(), XssSanitizer.text(request.itinerary()), trip::setItinerary);
        }
        if (request.inclusions() != null) {
            auditChange(trip, "inclusions", trip.getInclusions(), XssSanitizer.text(request.inclusions()), trip::setInclusions);
        }
        if (request.exclusions() != null) {
            auditChange(trip, "exclusions", trip.getExclusions(), XssSanitizer.text(request.exclusions()), trip::setExclusions);
        }
        if (request.active() != null) {
            auditChange(trip, "active", String.valueOf(trip.isActive()), String.valueOf(request.active()),
                    v -> trip.setActive(Boolean.parseBoolean(v)));
            if (!request.active()) {
                auditService.record("TRIP", trip.getId(), AuditAction.STATUS_CHANGE, "active", "true", "false");
            }
        }

        trips.save(trip);
        return toDetail(trip);
    }

    @Transactional
    public BatchResponse createBatch(UUID tripId, com.securetravels.crm.trip.dto.BatchCreateRequest request,
                                     UserPrincipal caller) {
        requireManager(caller);

        Trip trip = getTrip(tripId);
        requireFixedBatch(trip);
        if (batches.findByTripIdAndDepartureDate(tripId, request.departureDate()).isPresent()) {
            throw new ConflictException("Batch already exists for this trip on " + request.departureDate());
        }
        resolveGuide(request.guideId());

        Batch batch = new Batch(tripId, request.departureDate(), request.maxCapacity());
        batch.setGuideId(request.guideId());
        batch.setTransportPlan(XssSanitizer.text(request.transportPlan()));
        batch.setStatus(Batch.Status.OPEN);

        Batch saved = batches.save(batch);
        auditService.record("BATCH", saved.getId(), AuditAction.CREATE, "departure_date",
                null, saved.getDepartureDate().toString());
        return toBatchResponse(saved, trip);
    }

    /**
     * Module 3 — bulk-create a whole season of departures from a recurrence rule.
     * One transactional service method: the rule is expanded to concrete dates by
     * a pure date loop ({@link BatchRecurrence#dates()}) and each date reuses the
     * single-batch creation path above, so a generated batch is indistinguishable
     * from a hand-created one.
     *
     * <p>All-or-nothing: a failure part-way rolls the whole season back rather
     * than leaving a half-built schedule that nobody can tell is incomplete.
     * Dates that already have a batch are reported in {@code skippedDates}
     * instead of aborting the run, because extending an existing season is the
     * normal way this endpoint is reused.
     */
    @Transactional
    public BatchGenerateResponse generateSeason(UUID tripId, BatchGenerateRequest request,
                                                UserPrincipal caller) {
        requireManager(caller);

        Trip trip = getTrip(tripId);
        requireFixedBatch(trip);
        resolveGuide(request.guideId());

        List<LocalDate> dates;
        try {
            dates = request.recurrence().dates();
        } catch (IllegalArgumentException ex) {
            // Surface a malformed rule as a 400 rather than an opaque 500.
            throw new BadRequestException("Invalid recurrence rule: " + ex.getMessage());
        }
        if (dates.isEmpty()) {
            throw new BadRequestException("The recurrence rule produces no departure dates");
        }
        // Guide and transport plan are validated once above, not per date.
        String transportPlan = XssSanitizer.text(request.transportPlan());

        List<BatchResponse> created = new ArrayList<>();
        List<LocalDate> skipped = new ArrayList<>();
        for (LocalDate date : dates) {
            if (batches.findByTripIdAndDepartureDate(tripId, date).isPresent()) {
                skipped.add(date);
                continue;
            }
            Batch batch = new Batch(tripId, date, request.maxCapacity());
            batch.setGuideId(request.guideId());
            batch.setTransportPlan(transportPlan);
            batch.setStatus(Batch.Status.OPEN);
            Batch saved = batches.save(batch);
            auditService.record("BATCH", saved.getId(), AuditAction.CREATE, "departure_date",
                    null, saved.getDepartureDate().toString());
            created.add(toBatchResponse(saved, trip));
        }

        auditService.record("TRIP", tripId, AuditAction.UPDATE, "batches_generated",
                null, created.size() + " created, " + skipped.size() + " skipped");
        log.info("[batch-gen] trip={} created={} skipped={} window={}..{}",
                tripId, created.size(), skipped.size(), dates.get(0), dates.get(dates.size() - 1));

        return new BatchGenerateResponse(tripId, dates.size(), created.size(), skipped.size(),
                created, skipped);
    }

    @Transactional(readOnly = true)
    public List<BatchResponse> listBatches(UUID tripId) {
        Trip trip = getTrip(tripId);
        return batches.findByTripIdOrderByDepartureDateAsc(tripId).stream()
                .map(b -> toBatchResponse(b, trip))
                .toList();
    }

    @Transactional
    public BatchResponse updateBatch(UUID batchId, com.securetravels.crm.trip.dto.BatchUpdateRequest request,
                                     UserPrincipal caller) {
        requireManager(caller);

        Batch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("Batch not found: " + batchId));
        Trip trip = getTrip(batch.getTripId());

        if (request.departureDate() != null && !request.departureDate().equals(batch.getDepartureDate())) {
            batches.findByTripIdAndDepartureDate(batch.getTripId(), request.departureDate())
                    .filter(other -> !other.getId().equals(batch.getId()))
                    .ifPresent(other -> {
                        throw new ConflictException("Batch already exists for this trip on " + request.departureDate());
                    });
            auditService.record("BATCH", batch.getId(), AuditAction.UPDATE, "departure_date",
                    batch.getDepartureDate().toString(), request.departureDate().toString());
            batch.setDepartureDate(request.departureDate());
        }
        if (request.maxCapacity() != null && request.maxCapacity() != batch.getMaxCapacity()) {
            if (request.maxCapacity() < batch.getSeatsBooked()) {
                throw new BadRequestException("maxCapacity cannot be below seatsBooked (" + batch.getSeatsBooked() + ")");
            }
            auditService.record("BATCH", batch.getId(), AuditAction.UPDATE, "max_capacity",
                    String.valueOf(batch.getMaxCapacity()), String.valueOf(request.maxCapacity()));
            batch.setMaxCapacity(request.maxCapacity());
        }
        if (request.transportPlan() != null) {
            String sanitized = XssSanitizer.text(request.transportPlan());
            if (!sanitized.equals(batch.getTransportPlan())) {
                auditService.record("BATCH", batch.getId(), AuditAction.UPDATE, "transport_plan",
                        batch.getTransportPlan(), sanitized);
                batch.setTransportPlan(sanitized);
            }
        }
        if (Boolean.TRUE.equals(request.unassignGuide())) {
            if (batch.getGuideId() != null) {
                auditService.record("BATCH", batch.getId(), AuditAction.UPDATE, "guide",
                        batch.getGuideId().toString(), null);
                batch.setGuideId(null);
            }
        } else if (request.guideId() != null && !request.guideId().equals(batch.getGuideId())) {
            resolveGuide(request.guideId());
            auditService.record("BATCH", batch.getId(), AuditAction.UPDATE, "guide",
                    batch.getGuideId() == null ? null : batch.getGuideId().toString(), request.guideId().toString());
            batch.setGuideId(request.guideId());
        }
        if (request.status() != null && request.status() != batch.getStatus()) {
            if (batch.getStatus() == Batch.Status.CANCELLED) {
                throw new BadRequestException("A cancelled batch cannot change status");
            }
            if (request.status() == Batch.Status.READY_FOR_DEPARTURE) {
                var summary = complianceService.batchSummary(batch.getId());
                if (!summary.readyForDeparture()) {
                    throw new BadRequestException("Batch cannot be marked READY_FOR_DEPARTURE: compliance is "
                            + summary.compliancePercent() + "% (required "
                            + complianceService.readyThresholdPercent() + "%). Pending: " + summary.remainingItems()
                            + " verified items short of the gate.");
                }
            }
            auditService.record("BATCH", batch.getId(), AuditAction.STATUS_CHANGE, "status",
                    batch.getStatus().name(), request.status().name());
            batch.setStatus(request.status());
        }

        batches.save(batch);
        return toBatchResponse(batch, trip);
    }

    // ------------------------------------------------------------------ helpers

    private Trip getTrip(UUID id) {
        return trips.findById(id).orElseThrow(() -> new NotFoundException("Trip not found: " + id));
    }

    /** Only FIXED_BATCH trips are sold via departure batches (see Trip javadoc). */
    private void requireFixedBatch(Trip trip) {
        if (trip.getBookingType() != Trip.BookingType.FIXED_BATCH) {
            throw new BadRequestException("CUSTOM_FIT trips have no departure batches");
        }
    }

    private void resolveGuide(UUID vendorId) {
        if (vendorId != null && !vendors.existsByIdAndCategory(vendorId, Vendor.Category.GUIDE)) {
            throw new BadRequestException("Guide vendor not found: " + vendorId);
        }
    }

    private String resolveSlug(String name, String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided.trim();
        }
        return slugify(name);
    }

    static String slugify(String raw) {
        String base = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return base.isBlank() ? "trip" : base;
    }

    private String nextAvailableSlug(String base) {
        String candidate = base;
        int n = 2;
        while (trips.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + n;
            n++;
        }
        return candidate;
    }

    private static void requireManager(UserPrincipal caller) {
        if (caller.role() != Role.MANAGER && caller.role() != Role.ADMIN && caller.role() != Role.CEO) {
            throw new ForbiddenException("Only managers can manage the trip catalogue");
        }
    }

    private void auditChange(Trip trip, String field, String oldValue, String newValue, Consumer<String> apply) {
        if (newValue == null || newValue.equals(oldValue)) return;
        apply.accept(newValue);
        auditService.record("TRIP", trip.getId(), AuditAction.UPDATE, field, oldValue, newValue);
    }

    private TripDetailResponse toDetail(Trip trip) {
        List<BatchResponse> batchResponses = batches.findByTripIdOrderByDepartureDateAsc(trip.getId()).stream()
                .map(b -> toBatchResponse(b, trip))
                .toList();
        return new TripDetailResponse(
                trip.getId(), trip.getName(), trip.getSlug(), trip.getCategory(), trip.getBookingType(),
                trip.getDifficulty(), trip.getBaseCost(), trip.getDurationDays(), trip.getItinerary(),
                trip.getInclusions(), trip.getExclusions(), trip.isActive(), trip.getCreatedAt(),
                trip.getUpdatedAt(), batchResponses);
    }

    private BatchResponse toBatchResponse(Batch batch, Trip trip) {
        String guideName = batch.getGuideId() == null ? null
                : vendors.findById(batch.getGuideId()).map(Vendor::getName).orElse(null);
        return BatchResponse.of(batch, trip.getId(), guideName,
                props.getCapacity().getAlertFillPercent());
    }
}