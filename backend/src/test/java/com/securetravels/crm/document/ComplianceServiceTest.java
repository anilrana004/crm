package com.securetravels.crm.document;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.booking.Traveller;
import com.securetravels.crm.booking.TravellerRepository;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.document.dto.MarkChecklistRequest;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplianceServiceTest {

    @Mock private TravellerChecklistRepository checklists;
    @Mock private TravellerRepository travellerRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private TripRepository tripRepository;
    @Mock private BatchRepository batchRepository;
    @Mock private AuditService auditService;

    private ComplianceService service;
    private UserPrincipal opsCaller;

    private final UUID travellerId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCompliance().setReadyThresholdPercent(100);
        props.getCompliance().setMedicalThreshold(Trip.Difficulty.DIFFICULT);
        service = new ComplianceService(checklists, travellerRepository, bookingRepository,
                tripRepository, batchRepository, auditService, props);
        opsCaller = new UserPrincipal(UUID.randomUUID(), "ops@securetravels.in", "OPS", Role.OPS, true);
    }

    private Trip trip(Trip.Difficulty difficulty) {
        Trip t = new Trip();
        t.setDifficulty(difficulty);
        t.setBookingType(Trip.BookingType.FIXED_BATCH);
        return t;
    }

    private Traveller traveller(Integer age, boolean medicalFlag) {
        Traveller t = new Traveller(bookingId, "Traveller");
        t.setAge(age);
        t.setMedicalCertRequired(medicalFlag);
        return t;
    }

    private void stubTraveller(Traveller traveller, Trip trip) throws Exception {
        when(travellerRepository.findById(travellerId)).thenReturn(Optional.of(traveller));
        Booking booking = bookingWithTrip(tripId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(tripRepository.findById(tripId)).thenReturn(Optional.of(trip));
    }

    private static Booking bookingWithTrip(UUID tripId) throws Exception {
        java.lang.reflect.Constructor<Booking> ctor = Booking.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Booking booking = ctor.newInstance();
        java.lang.reflect.Field tripField = Booking.class.getDeclaredField("tripId");
        tripField.setAccessible(true);
        tripField.set(booking, tripId);
        return booking;
    }

    private void stubLazyChecklist() {
        lenient().when(checklists.findByTravellerId(travellerId)).thenReturn(Optional.empty());
    }

    @Test
    void allFourRequiredForAdultOnDifficultTrip() throws Exception {
        stubTraveller(traveller(30, false), trip(Trip.Difficulty.DIFFICULT));
        stubLazyChecklist();

        var view = service.travellerSummary(travellerId);

        assertThat(view.requiredCount()).isEqualTo(3);
        assertThat(view.color()).isEqualTo("RED");
    }

    @Test
    void medicalRequiredWhenTravellerFlaggedEvenOnEasyTrip() throws Exception {
        stubTraveller(traveller(30, true), trip(Trip.Difficulty.EASY));
        stubLazyChecklist();

        assertThat(service.travellerSummary(travellerId).requiredCount()).isEqualTo(3);
    }

    @Test
    void medicalNotRequiredOnEasyTripWhenUnflagged() throws Exception {
        stubTraveller(traveller(30, false), trip(Trip.Difficulty.EASY));
        stubLazyChecklist();

        assertThat(service.travellerSummary(travellerId).requiredCount()).isEqualTo(2);
    }

    @Test
    void minorConsentRequiredWhenAgeUnder18() throws Exception {
        stubTraveller(traveller(15, false), trip(Trip.Difficulty.MODERATE));
        stubLazyChecklist();

        assertThat(service.travellerSummary(travellerId).requiredCount()).isEqualTo(3);
    }

    @Test
    void markEmergencyContactVerifiesOnlyWhenNameAndPhonePresent() throws Exception {
        stubTraveller(traveller(30, false), trip(Trip.Difficulty.EASY));
        stubLazyChecklist();
        // Pre-populate an existing checklist so the service reads it before mark.
        when(checklists.findByTravellerId(travellerId)).thenReturn(Optional.of(new TravellerChecklist(travellerId)));

        var req = new MarkChecklistRequest(java.util.List.of(
                new MarkChecklistRequest.MarkChecklistItemRequest(ChecklistItem.EMERGENCY_CONTACT,
                        ComplianceStatus.VERIFIED)), null, null);

        assertThatThrownBy(() -> service.mark(travellerId, req, opsCaller))
                .isInstanceOf(com.securetravels.crm.common.exception.BadRequestException.class)
                .hasMessageContaining("EMERGENCY_CONTACT");
    }

    @Test
    void salesRoleCannotMarkChecklist() {
        UserPrincipal sales = new UserPrincipal(UUID.randomUUID(), "s@x.in", "Sales", Role.SALES, true);
        var req = new MarkChecklistRequest(java.util.List.of(
                new MarkChecklistRequest.MarkChecklistItemRequest(ChecklistItem.ID_PROOF,
                        ComplianceStatus.IN_PROGRESS)), null, null);

        assertThatThrownBy(() -> service.mark(travellerId, req, sales))
                .isInstanceOf(ForbiddenException.class);
    }
}