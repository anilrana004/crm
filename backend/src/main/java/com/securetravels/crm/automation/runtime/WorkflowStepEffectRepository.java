package com.securetravels.crm.automation.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowStepEffectRepository extends JpaRepository<WorkflowStepEffect, UUID> {

    List<WorkflowStepEffect> findByRunIdOrderByCreatedAtAsc(UUID runId);
}