package com.securetravels.crm.document;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.booking.Traveller;
import com.securetravels.crm.booking.TravellerRepository;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.document.dto.BatchComplianceResponse;
import com.securetravels.crm.document.dto.ComplianceItemView;
import com.securetravels.crm.document.dto.MarkChecklistRequest;
import com.securetravels.crm.document.dto.TravellerComplianceResponse;
import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 Module 1 — compliance. Computes the Red/Yellow/Green board and
 * enforces the READY_FOR_DEPARTURE hard gate server-side.
 *
 * Required items per traveller:
 *   • ID_PROOF / EMERGENCY_CONTACT — always
 *   • MEDICAL_FITNESS — trip difficulty at/above the configured threshold,
 *     or the traveller is flagged {@code medicalCertRequired}
 *   • MINOR_CONSENT — age < 18
 *
 * Only VERIFIED items count toward the gate. The gate threshold is
 * configurable (default 100%) via {@code app.compliance.ready-threshold-percent}.
 */
@Service
public class ComplianceService {

    private static final Set<Role> WRITERS = EnumSet.of(Role.OPS, Role.MANAGER, Role.ADMIN, Role.CEO);

    private final TravellerChecklistRepository checklists;
    private final TravellerRepository travellers;
    private final BookingRepository bookings;
    private final TripRepository trips;
    private final BatchRepository batches;
    private final AuditService auditService;
    private final AppProperties props;

    public ComplianceService(TravellerChecklistRepository checklists, TravellerRepository travellers,
                             BookingRepository bookings, TripRepository trips, BatchRepository batches,
                             AuditService auditService, AppProperties props) {
        this.checklists = checklists;
        this.travellers = travellers;
        this.bookings = bookings;
        this.trips = trips;
        this.batches = batches;
        this.auditService = auditService;
        this.props = props;
    }

    public int readyThresholdPercent() {
        return props.getCompliance().getReadyThresholdPercent();
    }

    @Transactional
    public TravellerComplianceResponse mark(UUID travellerId, MarkChecklistRequest request,
                                            UserPrincipal caller) {
        requireWriter(caller);
        Traveller traveller = getTraveller(travellerId);
        TravellerChecklist checklist = ensureChecklist(travellerId);
        Trip trip = tripFor(traveller);

        boolean changed = false;
        if (request.emergencyContactName() != null || request.emergencyContactPhone() != null) {
            checklist.setEmergencyContactName(request.emergencyContactName());
            checklist.setEmergencyContactPhone(request.emergencyContactPhone());
            changed = true;
        }
        if (request.items() != null) {
            for (MarkChecklistRequest.MarkChecklistItemRequest item : request.items()) {
                ComplianceStatus status = item.status();
                if (status == null) {
                    throw new BadRequestException("status is required for " + item.item());
                }
                switch (item.item()) {
                    case ID_PROOF -> changed |= apply(checklist.getId(), "id_proof", checklist.getIdProofStatus(),
                            status, v -> checklist.setIdProofStatus(v));
                    case MEDICAL_FITNESS -> changed |=
                            apply(checklist.getId(), "medical", checklist.getMedicalStatus(), status,
                                    v -> checklist.setMedicalStatus(v));
                    case EMERGENCY_CONTACT -> changed |= applyEmergency(checklist, status);
                    case MINOR_CONSENT -> changed |= apply(checklist.getId(), "minor", checklist.getMinorStatus(),
                            status, v -> checklist.setMinorStatus(v));
                }
            }
        }
        if (changed) {
            checklists.save(checklist);
        }
        return travellerSummary(travellerId);
    }

    @Transactional(readOnly = true)
    public TravellerComplianceResponse travellerSummary(UUID travellerId) {
        Traveller traveller = getTraveller(travellerId);
        TravellerChecklist checklist = checklists.findByTravellerId(travellerId).orElse(null);
        Trip trip = tripFor(traveller);
        return toTravellerView(traveller, trip, checklist);
    }

    @Transactional(readOnly = true)
    public BatchComplianceResponse batchSummary(UUID batchId) {
        Batch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("Batch not found: " + batchId));
        Trip trip = trips.findById(batch.getTripId())
                .orElseThrow(() -> new NotFoundException("Trip not found: " + batch.getTripId()));

        List<TravellerComplianceResponse> rows = new ArrayList<>();
        int totalRequired = 0;
        int totalVerified = 0;
        for (Booking booking : bookings.findByBatchIdAndStatus(batchId, Booking.Status.CONFIRMED)) {
            for (Traveller traveller : travellers.findByBookingIdOrderByCreatedAtAsc(booking.getId())) {
                TravellerComplianceResponse view = toTravellerView(traveller, trip,
                        checklists.findByTravellerId(traveller.getId()).orElse(null));
                rows.add(view);
                totalRequired += view.requiredCount();
                totalVerified += view.verifiedCount();
            }
        }

        int percent = totalRequired == 0 ? 100 : (totalVerified * 100 / totalRequired);
        int threshold = readyThresholdPercent();
        boolean ready = percent >= threshold;
        return new BatchComplianceResponse(batch.getId(), trip.getId(), trip.getName(),
                batch.getDepartureDate(), threshold, totalRequired, totalVerified, percent,
                color(totalRequired, totalVerified), ready, ready ? 0 : totalRequired - totalVerified, rows);
    }

    /** No side effects; the batch may or may not pass the gate right now. */
    @Transactional(readOnly = true)
    public BatchComplianceResponse readyCheck(UUID batchId) {
        return batchSummary(batchId);
    }

    /**
     * Links a confirmed document upload to the matching checklist item and
     * moves it MISSING → IN_PROGRESS (provided but not yet reviewed). No
     * checklist item exists for TRIP_PHOTO documents.
     */
    @Transactional
    public void linkDocument(UUID travellerId, Document.DocType docType, UUID documentId) {
        TravellerChecklist checklist = ensureChecklist(travellerId);
        boolean changed = false;
        switch (docType) {
            case ID_PROOF -> {
                if (checklist.getIdProofDocumentId() == null) {
                    checklist.setIdProofDocumentId(documentId);
                    changed = true;
                }
                if (checklist.getIdProofStatus() == ComplianceStatus.MISSING) {
                    checklist.setIdProofStatus(ComplianceStatus.IN_PROGRESS);
                    changed = true;
                }
            }
            case MEDICAL_CERT -> {
                if (checklist.getMedicalDocumentId() == null) {
                    checklist.setMedicalDocumentId(documentId);
                    changed = true;
                }
                if (checklist.getMedicalStatus() == ComplianceStatus.MISSING) {
                    checklist.setMedicalStatus(ComplianceStatus.IN_PROGRESS);
                    changed = true;
                }
            }
            case CONSENT_FORM -> {
                if (checklist.getMinorConsentDocumentId() == null) {
                    checklist.setMinorConsentDocumentId(documentId);
                    changed = true;
                }
                if (checklist.getMinorStatus() == ComplianceStatus.MISSING) {
                    checklist.setMinorStatus(ComplianceStatus.IN_PROGRESS);
                    changed = true;
                }
            }
            case TRIP_PHOTO -> { /* no checklist item */ }
        }
        if (changed) {
            checklists.save(checklist);
        }
    }

    @Transactional
    public BatchComplianceResponse markReadyForDeparture(UUID batchId, UserPrincipal caller) {
        requireWriter(caller);
        BatchComplianceResponse summary = batchSummary(batchId);
        if (!summary.readyForDeparture()) {
            throw new ConflictException("Batch cannot be marked READY_FOR_DEPARTURE: compliance is "
                    + summary.compliancePercent() + "% (required "
                    + summary.readyThresholdPercent() + "%). "
                    + summary.remainingItems() + " verified item(s) short of the gate.");
        }
        Batch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException("Batch not found: " + batchId));
        if (batch.getStatus() == Batch.Status.CANCELLED) {
            throw new ConflictException("A cancelled batch cannot be marked for departure");
        }
        if (batch.getStatus() != Batch.Status.READY_FOR_DEPARTURE) {
            auditService.statusChange("BATCH", batch.getId(), "status",
                    batch.getStatus().name(), "READY_FOR_DEPARTURE");
            batch.setStatus(Batch.Status.READY_FOR_DEPARTURE);
            batches.save(batch);
        }
        return summary;
    }

    // ------------------------------------------------------------------ internals

    private TravellerComplianceResponse toTravellerView(Traveller traveller, Trip trip,
                                                        TravellerChecklist checklist) {
        List<ComplianceItemView> items = new ArrayList<>();
        int required = 0;
        int verified = 0;

        items.add(itemView(ChecklistItem.ID_PROOF, true,
                statusOf(checklist, ChecklistItem.ID_PROOF), documentOf(checklist, ChecklistItem.ID_PROOF)));

        boolean medicalRequired = medicalRequired(traveller, trip);
        items.add(itemView(ChecklistItem.MEDICAL_FITNESS, medicalRequired,
                statusOf(checklist, ChecklistItem.MEDICAL_FITNESS), documentOf(checklist, ChecklistItem.MEDICAL_FITNESS)));

        items.add(itemView(ChecklistItem.EMERGENCY_CONTACT, true,
                statusOf(checklist, ChecklistItem.EMERGENCY_CONTACT), null));

        boolean minorRequired = minorRequired(traveller);
        items.add(itemView(ChecklistItem.MINOR_CONSENT, minorRequired,
                statusOf(checklist, ChecklistItem.MINOR_CONSENT), documentOf(checklist, ChecklistItem.MINOR_CONSENT)));

        for (ComplianceItemView item : items) {
            if (item.required()) {
                required++;
                if (item.status().verified()) verified++;
            }
        }
        int percent = required == 0 ? 100 : (verified * 100 / required);
        return new TravellerComplianceResponse(traveller.getId(), traveller.getFullName(), traveller.getAge(),
                traveller.getBookingId(), trip.getId(), required, verified, percent,
                color(required, verified), items);
    }

    private static ComplianceItemView itemView(ChecklistItem item, boolean required,
                                               ComplianceStatus status, UUID documentId) {
        return new ComplianceItemView(item, required, status, status.color(), documentId);
    }

    private ComplianceStatus statusOf(TravellerChecklist checklist, ChecklistItem item) {
        if (checklist == null) return ComplianceStatus.MISSING;
        return switch (item) {
            case ID_PROOF -> checklist.getIdProofStatus();
            case MEDICAL_FITNESS -> checklist.getMedicalStatus();
            case EMERGENCY_CONTACT -> checklist.getEmergencyStatus();
            case MINOR_CONSENT -> checklist.getMinorStatus();
        };
    }

    private UUID documentOf(TravellerChecklist checklist, ChecklistItem item) {
        if (checklist == null) return null;
        return switch (item) {
            case ID_PROOF -> checklist.getIdProofDocumentId();
            case MEDICAL_FITNESS -> checklist.getMedicalDocumentId();
            case EMERGENCY_CONTACT -> null;
            case MINOR_CONSENT -> checklist.getMinorConsentDocumentId();
        };
    }

    private boolean medicalRequired(Traveller traveller, Trip trip) {
        if (traveller.isMedicalCertRequired()) return true;
        Trip.Difficulty difficulty = trip.getDifficulty();
        return difficulty != null
                && difficulty.ordinal() >= props.getCompliance().getMedicalThreshold().ordinal();
    }

    private static boolean minorRequired(Traveller traveller) {
        return traveller.getAge() != null && traveller.getAge() < 18;
    }

    private static String color(int required, int verified) {
        if (required == 0 || verified == required) return "GREEN";
        return verified == 0 ? "RED" : "YELLOW";
    }

    private TravellerChecklist ensureChecklist(UUID travellerId) {
        return checklists.findByTravellerId(travellerId)
                .orElseGet(() -> checklists.save(new TravellerChecklist(travellerId)));
    }

    private boolean apply(UUID checklistId, String field, ComplianceStatus old, ComplianceStatus next,
                          java.util.function.Consumer<ComplianceStatus> setter) {
        if (old == next) return false;
        setter.accept(next);
        auditService.record("TRAVELLER_CHECKLIST", checklistId, AuditAction.STATUS_CHANGE,
                field, old.name(), next.name());
        return true;
    }

    private boolean applyEmergency(TravellerChecklist checklist, ComplianceStatus next) {
        if (next == ComplianceStatus.VERIFIED
                && (checklist.getEmergencyContactName() == null || checklist.getEmergencyContactPhone() == null)) {
            throw new BadRequestException(
                    "EMERGENCY_CONTACT cannot be VERIFIED without an emergencyContactName and emergencyContactPhone");
        }
        return apply(checklist.getId(), "emergency", checklist.getEmergencyStatus(), next,
                checklist::setEmergencyStatus);
    }

    private Traveller getTraveller(UUID id) {
        return travellers.findById(id).orElseThrow(() -> new NotFoundException("Traveller not found: " + id));
    }

    private Trip tripFor(Traveller traveller) {
        Booking booking = bookings.findById(traveller.getBookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found: " + traveller.getBookingId()));
        return trips.findById(booking.getTripId())
                .orElseThrow(() -> new NotFoundException("Trip not found: " + booking.getTripId()));
    }

    private static void requireWriter(UserPrincipal caller) {
        if (!WRITERS.contains(caller.role())) {
            throw new ForbiddenException("Only OPS/manager roles can manage compliance");
        }
    }
}