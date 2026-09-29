package com.securetravels.crm.automation.field;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-entity field allow-list (Phase 6 Module 1). This is the ONLY set of
 * field names a condition or an UPDATE_FIELD action may reference. Any other
 * string — including every SpEL/OGNL/EL/`T(...)`/`#{}`/`${}` shape — fails
 * validation. The registry is static configuration because the surface it
 * describes is small and stable; adding a field is a reviewed code change
 * (same discipline as the action registry).
 *
 * <p>Entities are addressed by the trigger's {@code entity} segment
 * ({@code lead}, {@code booking}, {@code payment}, {@code task},
 * {@code batch}, {@code customer}, {@code traveller}).
 */
public final class FieldRegistry {

    public static final String ENTITY_LEAD = "lead";
    public static final String ENTITY_BOOKING = "booking";
    public static final String ENTITY_PAYMENT = "payment";
    public static final String ENTITY_TASK = "task";
    public static final String ENTITY_BATCH = "batch";
    public static final String ENTITY_CUSTOMER = "customer";
    public static final String ENTITY_TRAVELLER = "traveller";

    private static final Map<String, Map<String, FieldDef>> REGISTRY = build();

    private FieldRegistry() {
    }

    public static Optional<FieldDef> lookup(String entity, String field) {
        Map<String, FieldDef> fields = REGISTRY.get(entity);
        return fields == null ? Optional.empty()
                : Optional.ofNullable(fields.get(field));
    }

    public static List<FieldDef> fieldsOf(String entity) {
        Map<String, FieldDef> fields = REGISTRY.get(entity);
        return fields == null ? List.of() : List.copyOf(fields.values());
    }

    public static List<FieldDef> writableOf(String entity) {
        return fieldsOf(entity).stream().filter(FieldDef::writable).toList();
    }

    public static boolean isKnownEntity(String entity) {
        return REGISTRY.containsKey(entity);
    }

    private static Map<String, Map<String, FieldDef>> build() {
        Map<String, Map<String, FieldDef>> m = new LinkedHashMap<>();

        m.put(ENTITY_LEAD, index(
                FieldDef.text("customerName", "leads.customer_name"),
                FieldDef.text("mobileNumber", "leads.mobile_number"),
                FieldDef.text("email", "leads.email"),
                FieldDef.textWritable("destination", "leads.destination"),
                FieldDef.date("travelDate", "leads.travel_date"),
                FieldDef.number("budget", "leads.budget"),
                FieldDef.number("numPersons", "leads.num_persons"),
                FieldDef.dateWritable("followUpDate", "leads.follow_up_date"),
                FieldDef.uuid("ownerId", "leads.owner_id"),
                FieldDef.uuid("customer360Id", "leads.customer360_id"),
                FieldDef.booleanField("consentGiven", "leads.consent_given"),
                FieldDef.dateTime("lastContactedAt", "leads.last_contacted_at"),
                FieldDef.enums("status", "leads.status",
                        "NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED", "LOST"),
                FieldDef.enums("source", "leads.source",
                        "GOOGLE_ADS", "FACEBOOK_ADS", "INSTAGRAM", "WEBSITE", "WHATSAPP",
                        "REFERRAL", "JUSTDIAL", "WALK_IN", "B2B", "EXISTING_CUSTOMER", "OTHER"),
                FieldDef.enums("heat", "leads.heat", "HOT", "WARM", "COLD")));

        m.put(ENTITY_BOOKING, index(
                FieldDef.enums("status", "bookings.status",
                        "QUOTATION", "CONFIRMED", "COMPLETED", "CANCELLED"),
                FieldDef.enums("bookingType", "bookings.booking_type", "FIXED_BATCH", "CUSTOM_FIT"),
                FieldDef.date("travelDate", "bookings.travel_date"),
                FieldDef.number("numTravellers", "bookings.num_travellers"),
                FieldDef.number("totalAmount", "bookings.total_amount"),
                FieldDef.uuid("customerId", "bookings.customer_id"),
                FieldDef.uuid("leadId", "bookings.lead_id"),
                FieldDef.uuid("batchId", "bookings.batch_id")));

        m.put(ENTITY_PAYMENT, index(
                FieldDef.enumsWritable("status", "payments.status",
                        "PENDING", "PARTIAL", "COMPLETED", "OVERDUE", "CANCELLED", "REFUNDED"),
                FieldDef.enums("amountType", "payments.amount_type", "ADVANCE", "BALANCE", "FULL"),
                FieldDef.date("dueDate", "payments.due_date"),
                FieldDef.number("amount", "payments.amount"),
                FieldDef.uuid("bookingId", "payments.booking_id")));

        m.put(ENTITY_TASK, index(
                FieldDef.enums("type", "tasks.type",
                        "INITIAL_CALL", "FOLLOW_UP_1D", "FOLLOW_UP_3D", "FOLLOW_UP_8D",
                        "FOLLOW_UP_15D", "QUOTATION", "PAYMENT_REMINDER", "OPS", "REVIEW", "CUSTOM"),
                FieldDef.enumsWritable("status", "tasks.status",
                        "PENDING", "COMPLETED", "OVERDUE", "CANCELLED"),
                FieldDef.dateTime("dueAt", "tasks.due_at"),
                FieldDef.uuidWritable("assigneeId", "tasks.assignee_id"),
                FieldDef.uuid("leadId", "tasks.lead_id"),
                FieldDef.uuid("bookingId", "tasks.booking_id")));

        m.put(ENTITY_BATCH, index(
                FieldDef.enumsWritable("status", "batches.status",
                        "OPEN", "CLOSED", "CANCELLED", "READY_FOR_DEPARTURE"),
                FieldDef.date("departureDate", "batches.departure_date"),
                FieldDef.number("seatsBooked", "batches.seats_booked"),
                FieldDef.number("maxCapacity", "batches.max_capacity"),
                FieldDef.number("fillPercent", "batches (derived: seats_booked / max_capacity)"),
                FieldDef.uuid("tripId", "batches.trip_id")));

        m.put(ENTITY_CUSTOMER, index(
                FieldDef.text("mobileNumber", "customer360.mobile_number"),
                FieldDef.text("email", "customer360.email"),
                FieldDef.booleanField("marketingOptIn", "customer360.marketing_opt_in"),
                FieldDef.number("totalTrips", "customer360.total_trips"),
                FieldDef.number("totalSpent", "customer360.total_spent"),
                FieldDef.date("lastTripDate", "customer360.last_trip_date")));

        m.put(ENTITY_TRAVELLER, index(
                FieldDef.number("age", "travellers.age"),
                FieldDef.booleanField("medicalCertRequired", "travellers.medical_cert_required")));

        return Map.copyOf(m);
    }

    private static Map<String, FieldDef> index(FieldDef... fields) {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        for (FieldDef f : fields) {
            m.put(f.name(), f);
        }
        return Map.copyOf(m);
    }
}