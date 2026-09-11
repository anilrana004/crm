package com.securetravels.crm.common.config;

import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.task.FollowUpAutomation;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Local/dev convenience: seeds the five demo accounts and a starter set of
 * demo leads once when the corresponding tables are empty and
 * app.bootstrap-demo-data=true (production sets it false — real data is
 * provisioned via proper admin flows in Phase 2+).
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppProperties props;
    private final UserRepository users;
    private final TripRepository trips;
    private final LeadRepository leads;
    private final TaskRepository tasks;
    private final FollowUpAutomation automation;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(AppProperties props, UserRepository users, TripRepository trips,
                          LeadRepository leads, TaskRepository tasks, FollowUpAutomation automation,
                          PasswordEncoder passwordEncoder) {
        this.props = props;
        this.users = users;
        this.trips = trips;
        this.leads = leads;
        this.tasks = tasks;
        this.automation = automation;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!props.isBootstrapDemoData()) return;
        seedUsersIfEmpty();
        seedTripsIfEmpty();
        seedLeadsIfEmpty();
        seedInitialCallTasksIfMissing();
    }

    /** Backfill the 5-min first-call tasks for demo leads already in the DB
     * (the automation path normally fires on creation; for pre-seeded demo
     * rows the task comes from here so the follow-up board is populated). */
    private void seedInitialCallTasksIfMissing() {
        List<Lead> open = leads.findAll().stream()
                .filter(l -> l.getStatus() != Lead.Status.LOST && l.getOwnerId() != null)
                .filter(l -> tasks.countByLeadIdAndType(l.getId(), com.securetravels.crm.task.Task.Type.INITIAL_CALL) == 0)
                .toList();
        open.forEach(l -> automation.onLeadCreated(l.getId(), l.getOwnerId()));
        if (!open.isEmpty()) {
            log.info("[demo-data] backfilled {} follow-up INITIAL_CALL tasks", open.size());
        }
    }

    private void seedUsersIfEmpty() {
        if (users.count() > 0) return;

        List<User> demo = List.of(
                new User("admin@securetravels.in", password("admin123"), "Admin Admin", Role.ADMIN, "9876000001"),
                new User("manager@securetravels.in", password("manager123"), "Manager Manager", Role.MANAGER, "9876000002"),
                new User("sales.ravi@securetravels.in", password("sales123"), "Ravi Singh", Role.SALES, "9876000003"),
                new User("sales.meera@securetravels.in", password("sales123"), "Meera Joshi", Role.SALES, "9876000004"),
                new User("ops.suresh@securetravels.in", password("ops123"), "Suresh Rawat", Role.OPS, "9876000005"));
        users.saveAll(demo);
        log.info("[demo-data] seeded 5 demo users (bcrypt strength 12)");
    }

    private void seedTripsIfEmpty() {
        if (trips.count() > 0) return;

        List<Trip> demo = List.of(
                trip("Kedarnath Yatra", "kedarnath-yatra", Trip.Category.PILGRIMAGE, Trip.BookingType.FIXED_BATCH,
                        "12000.00", 5, "Day 1: Haridwar → Gaurikund | Day 2: Trek to Kedarnath | ..."),
                trip("Bali Family Honeymoon", "bali-family-honeymoon", Trip.Category.LEISURE, Trip.BookingType.CUSTOM_FIT,
                        "150000.00", 7, "Day 1: Arrive Denpasar | Day 2-6: Beaches, culture, spa | Day 7: Depart"));
        trips.saveAll(demo);
        log.info("[demo-data] seeded 2 demo trips");
    }

    private Trip trip(String name, String slug, Trip.Category category, Trip.BookingType bookingType,
                      String baseCost, int durationDays, String itinerary) {
        Trip t = new Trip();
        t.setName(name);
        t.setSlug(slug);
        t.setCategory(category);
        t.setBookingType(bookingType);
        t.setBaseCost(new BigDecimal(baseCost));
        t.setDurationDays(durationDays);
        t.setItinerary(itinerary);
        t.setInclusions("Sightseeing, transfers, 4* hotels");
        t.setExclusions("Flights, travel insurance, personal expenses");
        return t;
    }

    private void seedLeadsIfEmpty() {
        if (leads.count() > 0) return;

        Map<String, User> owners = users.findAll().stream()
                .collect(Collectors.toMap(User::getEmail, Function.identity()));

        List<String> ownerEmails = List.of(
                "sales.ravi@securetravels.in", "sales.meera@securetravels.in");
        if (ownerEmails.stream().anyMatch(e -> owners.get(e) == null)) {
            log.warn("[demo-data] skipping demo-lead seed: expected Sales demo users not present");
            return;
        }

        LocalDate today = LocalDate.now();
        Map<String, Trip> tripsByName = trips.findAll().stream()
                .collect(Collectors.toMap(Trip::getName, Function.identity()));
        UUID baliTrip = tripsByName.get("Bali Family Honeymoon") == null ? null
                : tripsByName.get("Bali Family Honeymoon").getId();
        List<Lead> demo = List.of(
                demoLead(owners.get("sales.ravi@securetravels.in"), "Priya Sharma", "+91 98300 11223",
                        "Priya Sharma's family of 4 wants a Bali honeymoon-style trip.", Lead.Source.WEBSITE,
                        "Bali", baliTrip, today.plusMonths(3), 4, new BigDecimal("350000"),
                        Lead.Status.QUOTATION_SENT, Lead.Heat.HOT, today.plusDays(2)),
                demoLead(owners.get("sales.ravi@securetravels.in"), "Amit Patel", "+91 91234 55667",
                        "Couple planning a short Singapore trip in December.", Lead.Source.INSTAGRAM,
                        "Singapore", null, today.plusMonths(2), 2, new BigDecimal("110000"),
                        Lead.Status.NEW, Lead.Heat.WARM, today.plusDays(5)),
                demoLead(owners.get("sales.ravi@securetravels.in"), "Ramesh Kumar", "+91 99880 33445",
                        "Asked about Andaman but budget was too tight.", Lead.Source.WHATSAPP,
                        "Andaman", null, today.plusMonths(6), 3, new BigDecimal("75000"),
                        Lead.Status.LOST, Lead.Heat.COLD, null),
                demoLead(owners.get("sales.meera@securetravels.in"), "Sneha Reddy", "+91 81000 44556",
                        "Anniversary trip to the Maldives; decision expected this week.", Lead.Source.FACEBOOK_ADS,
                        "Maldives", null, today.plusMonths(2), 2, new BigDecimal("280000"),
                        Lead.Status.INTERESTED, Lead.Heat.HOT, today.plusDays(1)),
                demoLead(owners.get("sales.meera@securetravels.in"), "Vikram Nair", "+91 90999 88776",
                        "Recommended by existing customer; Kerala houseboat enquiry.", Lead.Source.REFERRAL,
                        "Kerala", null, today.plusMonths(4), 6, new BigDecimal("60000"),
                        Lead.Status.NEW, Lead.Heat.WARM, today.plusDays(3)),
                demoLead(owners.get("sales.meera@securetravels.in"), "Ananya Das", "+91 90600 22110",
                        "Early-stage Dubai shopping trip research.", Lead.Source.WEBSITE,
                        "Dubai", null, today.plusMonths(5), 2, new BigDecimal("90000"),
                        Lead.Status.NEW, Lead.Heat.COLD, today.plusDays(7)));

        leads.saveAll(demo);
        log.info("[demo-data] seeded {} demo leads", demo.size());
    }

    private Lead demoLead(User owner, String name, String mobile, String remarks, Lead.Source source,
                          String destination, UUID tripId, LocalDate travelDate, int numPersons,
                          BigDecimal budget, Lead.Status status, Lead.Heat heat, LocalDate followUpDate) {
        Lead lead = new Lead();
        lead.setCustomerName(name);
        lead.setMobileNumber(mobile);
        lead.setMobileDigits(PhoneUtils.normalize(mobile));
        lead.setWhatsappNumber(mobile);
        lead.setRemarks(remarks);
        lead.setSource(source);
        lead.setDestination(destination);
        lead.setTripId(tripId);
        lead.setTravelDate(travelDate);
        lead.setNumPersons(numPersons);
        lead.setBudget(budget);
        lead.setOwnerId(owner.getId());
        lead.setStatus(status);
        lead.setHeat(heat);
        lead.setFollowUpDate(followUpDate);
        lead.setConsentGiven(true);
        lead.setConsentCapturedAt(Instant.now());
        lead.setConsentScope("calls,email,whatsapp");
        lead.setLastContactedAt(status == Lead.Status.LOST ? null : Instant.now().minusSeconds(86400));
        lead.setCreatedBy(owner.getId());
        if (status == Lead.Status.LOST) lead.setLostReason(Lead.LostReason.PRICE_TOO_HIGH);
        return lead;
    }

    private String password(String raw) {
        return passwordEncoder.encode(raw);
    }
}