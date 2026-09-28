package com.securetravels.crm.document;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.booking.Traveller;
import com.securetravels.crm.booking.TravellerRepository;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.document.dto.BatchComplianceResponse;
import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Boundary tests for the Red/Yellow/Green compliance computation.
 *
 * <p>{@code ComplianceServiceTest} covers <em>which</em> items a traveller owes
 * (the required-count matrix). This class covers the arithmetic around it and
 * the two edges that decide whether a batch may legally depart:
 *
 * <ul>
 *   <li>exactly at the threshold ({@code verified == required}) — must be GREEN
 *       and must pass the gate, not fail it;</li>
 *   <li>one document short ({@code verified == required - 1}) — must be YELLOW
 *       and must fail the gate.</li>
 * </ul>
 *
 * <p>Both are boundaries an off-by-one silently gets wrong, and both fail
 * silently: a batch wrongly allowed to depart strands travellers without ID
 * proof, and one wrongly blocked costs the operator a phone call.
 *
 * <p>The percentage is integer division ({@code verified * 100 / required}), so
 * 2-of-3 is 66, not 67. That is asserted explicitly below so switching to
 * rounding later is a deliberate, visible change rather than quiet drift.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceBoundaryTest {

    @Mock private TravellerChecklistRepository checklists;
    @Mock private TravellerRepository travellerRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private TripRepository tripRepository;
    @Mock private BatchRepository batchRepository;
    @Mock private AuditService auditService;

    private ComplianceService service;

    private final UUID tripId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID travellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCompliance().setReadyThresholdPercent(100);
        props.getCompliance().setMedicalThreshold(Trip.Difficulty.DIFFICULT);
        service = new ComplianceService(checklists, travellerRepository, bookingRepository,
                tripRepository, batchRepository, auditService, props);
    }

    // ------------------------------------------------------------ traveller level

    @Test
    void oneDocumentShortOfCompleteIsYellowAndBelowThreshold() throws Exception {
        // EASY + unflagged adult owes exactly two: ID_PROOF, EMERGENCY_CONTACT.
        stubSingleTraveller(easyTrip());
        when(checklists.findByTravellerId(travellerId))
                .thenReturn(Optional.of(checklist(ComplianceStatus.VERIFIED, null)));

        var view = service.travellerSummary(travellerId);

        assertThat(view.requiredCount()).isEqualTo(2);
        assertThat(view.verifiedCount()).isEqualTo(1);
        assertThat(view.compliancePercent()).isEqualTo(50);
        assertThat(view.color()).isEqualTo("YELLOW");
        assertThat(view.fullyCompliant()).isFalse();
    }

    @Test
    void exactlyAtThresholdIsGreenAndFullyCompliant() throws Exception {
        stubSingleTraveller(easyTrip());
        when(checklists.findByTravellerId(travellerId))
                .thenReturn(Optional.of(checklist(ComplianceStatus.VERIFIED, ComplianceStatus.VERIFIED)));

        var view = service.travellerSummary(travellerId);

        assertThat(view.requiredCount()).isEqualTo(2);
        assertThat(view.verifiedCount()).isEqualTo(2);
        assertThat(view.compliancePercent()).isEqualTo(100);
        assertThat(view.color()).isEqualTo("GREEN");
        assertThat(view.fullyCompliant()).isTrue();
    }

    @Test
    void nothingVerifiedIsRed() throws Exception {
        stubSingleTraveller(easyTrip());
        when(checklists.findByTravellerId(travellerId))
                .thenReturn(Optional.of(new TravellerChecklist(travellerId)));

        var view = service.travellerSummary(travellerId);

        assertThat(view.verifiedCount()).isZero();
        assertThat(view.compliancePercent()).isZero();
        assertThat(view.color()).isEqualTo("RED");
    }

    @Test
    void inProgressDoesNotCountTowardTheGate() throws Exception {
        // The trap: an uploaded-but-unreviewed document is YELLOW on its own item
        // and must not move the batch percentage, or a batch departs unreviewed.
        stubSingleTraveller(easyTrip());
        when(checklists.findByTravellerId(travellerId))
                .thenReturn(Optional.of(checklist(ComplianceStatus.IN_PROGRESS, ComplianceStatus.VERIFIED)));

        var view = service.travellerSummary(travellerId);

        assertThat(view.verifiedCount()).isEqualTo(1);
        assertThat(view.compliancePercent()).isEqualTo(50);
        assertThat(view.color()).isEqualTo("YELLOW");
    }

    @Test
    void verifyingAnItemThatIsNotRequiredDoesNotInflateTheCount() throws Exception {
        // MEDICAL_FITNESS is not owed on an EASY unflagged adult. Verifying it
        // anyway must not add to the verified count.
        stubSingleTraveller(easyTrip());
        TravellerChecklist checklist = checklist(ComplianceStatus.VERIFIED, ComplianceStatus.VERIFIED);
        checklist.setMedicalStatus(ComplianceStatus.VERIFIED);
        when(checklists.findByTravellerId(travellerId)).thenReturn(Optional.of(checklist));

        var view = service.travellerSummary(travellerId);

        assertThat(view.requiredCount()).isEqualTo(2);
        assertThat(view.verifiedCount()).isEqualTo(2);
        assertThat(view.compliancePercent()).isEqualTo(100);
        assertThat(view.color()).isEqualTo("GREEN");
    }

    // ---------------------------------------------------------------- batch level

    @Test
    void batchExactlyAtThresholdPassesTheGate() throws Exception {
        stubBatch(easyTrip());
        oneConfirmedBooking(travellerWith(travellerId, "Adult", 30, false,
                checklist(ComplianceStatus.VERIFIED, ComplianceStatus.VERIFIED)));

        BatchComplianceResponse view = service.batchSummary(batchId);

        assertThat(view.totalRequired()).isEqualTo(2);
        assertThat(view.totalVerified()).isEqualTo(2);
        assertThat(view.compliancePercent()).isEqualTo(100);
        assertThat(view.color()).isEqualTo("GREEN");
        assertThat(view.readyForDeparture()).isTrue();
        assertThat(view.remainingItems()).isZero();
    }

    @Test
    void batchOneDocumentShortOfThresholdBlocksTheGate() throws Exception {
        // 1 of 2 verified: the exact "one document short" edge.
        stubBatch(easyTrip());
        oneConfirmedBooking(travellerWith(travellerId, "Adult", 30, false,
                checklist(ComplianceStatus.VERIFIED, null)));

        BatchComplianceResponse view = service.batchSummary(batchId);

        assertThat(view.totalRequired()).isEqualTo(2);
        assertThat(view.totalVerified()).isEqualTo(1);
        assertThat(view.compliancePercent()).isEqualTo(50);
        assertThat(view.color()).isEqualTo("YELLOW");
        assertThat(view.readyForDeparture()).isFalse();
        assertThat(view.remainingItems()).isEqualTo(1);
    }

    @Test
    void batchAggregatesAcrossEveryTravellerOnTheBatch() throws Exception {
        // DIFFICULT trip: an adult owes 3 (ID_PROOF, MEDICAL_FITNESS,
        // EMERGENCY_CONTACT), a minor owes those plus MINOR_CONSENT = 4.
        // Totals: required 7, verified 3 + 1 = 4 -> 57%, 3 outstanding.
        UUID minorId = UUID.randomUUID();
        stubBatch(difficultTrip());

        TravellerChecklist adultDone = checklist(ComplianceStatus.VERIFIED, ComplianceStatus.VERIFIED);
        adultDone.setMedicalStatus(ComplianceStatus.VERIFIED);

        oneConfirmedBooking(
                travellerWith(travellerId, "Adult", 30, false, adultDone),
                travellerWith(minorId, "Minor", 15, false,
                        checklist(ComplianceStatus.VERIFIED, null)));

        BatchComplianceResponse view = service.batchSummary(batchId);

        assertThat(view.totalRequired()).isEqualTo(7);
        assertThat(view.totalVerified()).isEqualTo(4);
        assertThat(view.compliancePercent()).isEqualTo(57);
        assertThat(view.color()).isEqualTo("YELLOW");
        assertThat(view.readyForDeparture()).isFalse();
        assertThat(view.remainingItems()).isEqualTo(3);
        assertThat(view.travellers()).hasSize(2);
    }

    @Test
    void batchPercentTruncatesRatherThanRounds() throws Exception {
        // Adult on a DIFFICULT trip owes 3; two verified -> 200/3 = 66, not 67.
        stubBatch(difficultTrip());
        oneConfirmedBooking(travellerWith(travellerId, "Adult", 30, false,
                checklist(ComplianceStatus.VERIFIED, ComplianceStatus.VERIFIED)));

        BatchComplianceResponse view = service.batchSummary(batchId);

        assertThat(view.totalRequired()).isEqualTo(3);
        assertThat(view.totalVerified()).isEqualTo(2);
        assertThat(view.compliancePercent()).isEqualTo(66);
        assertThat(view.color()).isEqualTo("YELLOW");
        assertThat(view.readyForDeparture()).isFalse();
    }

    @Test
    void batchWithNoTravellersIsGreenAndReadyWithNothingOutstanding() throws Exception {
        // Nothing owed is not the same as everything owed: a batch with no
        // confirmed travellers has zero required items, so it is GREEN, 100%,
        // ready and zero outstanding — not RED.
        stubBatch(easyTrip());
        when(bookingRepository.findByBatchIdAndStatus(batchId, Booking.Status.CONFIRMED))
                .thenReturn(List.of());

        BatchComplianceResponse view = service.batchSummary(batchId);

        assertThat(view.totalRequired()).isZero();
        assertThat(view.totalVerified()).isZero();
        assertThat(view.compliancePercent()).isEqualTo(100);
        assertThat(view.color()).isEqualTo("GREEN");
        assertThat(view.readyForDeparture()).isTrue();
        assertThat(view.remainingItems()).isZero();
        assertThat(view.travellers()).isEmpty();
    }

    // -------------------------------------------------------------------- helpers

    private Trip easyTrip() {
        return trip(Trip.Difficulty.EASY);
    }

    private Trip difficultTrip() {
        return trip(Trip.Difficulty.DIFFICULT);
    }

    private Trip trip(Trip.Difficulty difficulty) {
        Trip t = new Trip();
        t.setDifficulty(difficulty);
        t.setBookingType(Trip.BookingType.FIXED_BATCH);
        t.setName("Trip " + difficulty);
        return t;
    }

    /** Builds a checklist; a {@code null} status means "not verified" (MISSING). */
    private TravellerChecklist checklist(ComplianceStatus idProof, ComplianceStatus emergency) {
        TravellerChecklist c = new TravellerChecklist(travellerId);
        c.setIdProofStatus(idProof == null ? ComplianceStatus.MISSING : idProof);
        c.setEmergencyStatus(emergency == null ? ComplianceStatus.MISSING : emergency);
        return c;
    }

    /** Registers a traveller on the confirmed booking and stubs its checklist. */
    private Traveller travellerWith(UUID id, String name, int age, boolean medicalFlag,
                                    TravellerChecklist checklist) {
        if (checklist != null) {
            when(checklists.findByTravellerId(id)).thenReturn(Optional.of(checklist));
        }
        Traveller t = new Traveller(bookingId, name);
        t.setAge(age);
        t.setMedicalCertRequired(medicalFlag);
        setField(t, "id", id);
        return t;
    }

    /** Stubs the read path used by {@code travellerSummary}. */
    private void stubSingleTraveller(Trip trip) throws Exception {
        when(travellerRepository.findById(travellerId))
                .thenReturn(Optional.of(travellerWith(travellerId, "Adult", 30, false, null)));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(bookingForTrip()));
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
    }

    /** Stubs the read path used by {@code batchSummary}, minus the traveller list. */
    private void stubBatch(Trip trip) {
        Batch batch = new Batch(tripId, LocalDate.of(2026, 12, 1), 10);
        setField(batch, "id", batchId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
    }

    private void oneConfirmedBooking(Traveller... travellers) {
        when(bookingRepository.findByBatchIdAndStatus(batchId, Booking.Status.CONFIRMED))
                .thenReturn(List.of(bookingForTrip()));
        when(travellerRepository.findByBookingIdOrderByCreatedAtAsc(bookingId))
                .thenReturn(List.of(travellers));
    }

    private Booking bookingForTrip() {
        try {
            java.lang.reflect.Constructor<Booking> ctor = Booking.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Booking booking = ctor.newInstance();
            setField(booking, "id", bookingId);
            setField(booking, "tripId", tripId);
            return booking;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
