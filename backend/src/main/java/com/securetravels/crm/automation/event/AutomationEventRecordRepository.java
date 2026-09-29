package com.securetravels.crm.automation.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AutomationEventRecordRepository extends JpaRepository<AutomationEventRecord, UUID> {
}