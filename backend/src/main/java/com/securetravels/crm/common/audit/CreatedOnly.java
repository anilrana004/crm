package com.securetravels.crm.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/** Base for entities whose table carries only created_at. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedOnly {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Instant getCreatedAt() { return createdAt; }
}