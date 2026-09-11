package com.securetravels.crm.booking;

import com.securetravels.crm.common.audit.CreatedOnly;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A person travelling under a booking. Link to customer360 is set when the
 * person can be identified; PII lives only here and in customer360 (I8).
 */
@Entity
@Table(name = "travellers", indexes = {
        @Index(name = "idx_travellers_booking", columnList = "booking_id")
})
public class Traveller extends CreatedOnly {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "customer360_id")
    private UUID customer360Id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column
    private Integer age;

    @Column(length = 10)
    private String gender;

    @Column(length = 20)
    private String phone;

    @Column(name = "medical_cert_required", nullable = false)
    private boolean medicalCertRequired;

    protected Traveller() {}

    public Traveller(UUID bookingId, String fullName) {
        this.bookingId = bookingId;
        this.fullName = fullName;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getCustomer360Id() { return customer360Id; }
    public String getFullName() { return fullName; }
    public Integer getAge() { return age; }
    public String getGender() { return gender; }
    public String getPhone() { return phone; }
    public boolean isMedicalCertRequired() { return medicalCertRequired; }

    public void setCustomer360Id(UUID customer360Id) { this.customer360Id = customer360Id; }
    public void setAge(Integer age) { this.age = age; }
    public void setGender(String gender) { this.gender = gender; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setMedicalCertRequired(boolean medicalCertRequired) { this.medicalCertRequired = medicalCertRequired; }
}