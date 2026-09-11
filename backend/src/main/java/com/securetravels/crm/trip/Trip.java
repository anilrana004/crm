package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Catalogue trip. {@code bookingType} discriminates the two booking flows:
 * FIXED_BATCH (sold via departure batches) vs CUSTOM_FIT (bespoke pricing).
 * Full CRUD arrives with Module 3; Module 1 reads only (lead filters + form
 * pickers).
 */
@Entity
@Table(name = "trips")
public class Trip extends Auditable {

    public enum Category { TREK, PILGRIMAGE, LEISURE, CUSTOM }
    public enum BookingType { FIXED_BATCH, CUSTOM_FIT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", nullable = false, length = 30)
    private BookingType bookingType;

    @Column(name = "base_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseCost;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(columnDefinition = "text")
    private String itinerary;

    @Column(columnDefinition = "text")
    private String inclusions;

    @Column(columnDefinition = "text")
    private String exclusions;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Trip() {}

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public Category getCategory() { return category; }
    public BookingType getBookingType() { return bookingType; }
    public BigDecimal getBaseCost() { return baseCost; }
    public int getDurationDays() { return durationDays; }
    public String getItinerary() { return itinerary; }
    public String getInclusions() { return inclusions; }
    public String getExclusions() { return exclusions; }
    public boolean isActive() { return active; }

    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setCategory(Category category) { this.category = category; }
    public void setBookingType(BookingType bookingType) { this.bookingType = bookingType; }
    public void setBaseCost(BigDecimal baseCost) { this.baseCost = baseCost; }
    public void setDurationDays(int durationDays) { this.durationDays = durationDays; }
    public void setItinerary(String itinerary) { this.itinerary = itinerary; }
    public void setInclusions(String inclusions) { this.inclusions = inclusions; }
    public void setExclusions(String exclusions) { this.exclusions = exclusions; }
    public void setActive(boolean active) { this.active = active; }
}