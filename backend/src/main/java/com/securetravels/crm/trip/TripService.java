package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.trip.dto.BatchResponse;
import com.securetravels.crm.trip.dto.TripCreateRequest;
import com.securetravels.crm.trip.dto.TripDetailResponse;
import com.securetravels.crm.trip.dto.TripUpdateRequest;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class TripService {

    private final TripRepository trips;
    private final BatchRepository batches;
    private final GuideRepository guides;
    private final AuditService auditService;

    public TripService(TripRepository trips, BatchRepository batches, GuideRepository guides,
                       AuditService auditService) {
        this.trips = trips;
        this.batches = batches;
        this.guides = guides;
        this.auditService = auditService;
    }

    @Transactional
    public TripDetailResponse create(TripCreateRequest request, UserPrincipal caller) {
        requireManager(caller);

        Trip trip = new Trip();
        trip.setName(request.name());
        trip.setSlug(nextAvailableSlug(resolveSlug(request.name(), request.slug())));
        trip.setCategory(request.category());
        trip.setBookingType(request.bookingType());
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
        if (trip.getBookingType() != Trip.BookingType.FIXED_BATCH) {
            throw new BadRequestException("CUSTOM_FIT trips have no departure batches");
        }
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

    private void resolveGuide(UUID guideId) {
        if (guideId != null && !guides.existsById(guideId)) {
            throw new BadRequestException("Guide not found: " + guideId);
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
                trip.getBaseCost(), trip.getDurationDays(), trip.getItinerary(), trip.getInclusions(),
                trip.getExclusions(), trip.isActive(), trip.getCreatedAt(), trip.getUpdatedAt(), batchResponses);
    }

    private BatchResponse toBatchResponse(Batch batch, Trip trip) {
        String guideName = batch.getGuideId() == null ? null
                : guides.findById(batch.getGuideId()).map(Guide::getFullName).orElse(null);
        return new BatchResponse(
                batch.getId(), trip.getId(), batch.getDepartureDate(), batch.getMaxCapacity(),
                batch.getSeatsBooked(), batch.seatsAvailable(), batch.getGuideId(), guideName,
                batch.getTransportPlan(), batch.getStatus());
    }
}