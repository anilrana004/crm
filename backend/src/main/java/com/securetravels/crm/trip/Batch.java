package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A scheduled departure of a FIXED_BATCH trip. One guide, one shared
 * transport plan, one live seat pool. CUSTOM_FIT trips never have batches.
 *
 * I3: seats_available = max_capacity - seats_booked is DERIVED (getter),
 * never stored. Seat writes happen inside a PESSIMISTIC_WRITE lock on the
 * batch row in the service layer.
 */
@Entity
@Table(name = "batches",
        uniqueConstraints = @UniqueConstraint(name = "uq_batch_trip_departure", columnNames = {"trip_id", "departure_date"}),
        indexes = {
                @Index(name = "idx_batches_departure", columnList = "departure_date")
        })
public class Batch extends Auditable {

    public enum Status { OPEN, CLOSED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "max_capacity", nullable = false)
    private int maxCapacity;

    @Column(name = "seats_booked", nullable = false)
    private int seatsBooked;

    @Column(name = "guide_id")
    private UUID guideId;

    @Column(name = "transport_plan", columnDefinition = "text")
    private String transportPlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    protected Batch() {}

    public Batch(UUID tripId, LocalDate departureDate, int maxCapacity) {
        this.tripId = tripId;
        this.departureDate = departureDate;
        this.maxCapacity = maxCapacity;
    }

    /** Derived pool — see I3. */
    public int seatsAvailable() {
        return Math.max(0, maxCapacity - seatsBooked);
    }

    public UUID getId() { return id; }
    public UUID getTripId() { return tripId; }
    public LocalDate getDepartureDate() { return departureDate; }
    public int getMaxCapacity() { return maxCapacity; }
    public int getSeatsBooked() { return seatsBooked; }
    public UUID getGuideId() { return guideId; }
    public String getTransportPlan() { return transportPlan; }
    public Status getStatus() { return status; }

    public void setDepartureDate(LocalDate departureDate) { this.departureDate = departureDate; }
    public void setMaxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; }
    public void setSeatsBooked(int seatsBooked) { this.seatsBooked = seatsBooked; }
    public void setGuideId(UUID guideId) { this.guideId = guideId; }
    public void setTransportPlan(String transportPlan) { this.transportPlan = transportPlan; }
    public void setStatus(Status status) { this.status = status; }

    void addSeats(int n) { this.seatsBooked += n; }
    void releaseSeats(int n) { this.seatsBooked = Math.max(0, this.seatsBooked - n); }
}