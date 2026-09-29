package com.securetravels.crm.automation.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowScheduledStepRepository extends JpaRepository<WorkflowScheduledStep, UUID> {

    Optional<WorkflowScheduledStep> findByRunIdAndStepId(UUID runId, String stepId);

    List<WorkflowScheduledStep> findByRunId(UUID runId);

    void deleteByRunId(UUID runId);
}