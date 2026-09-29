package com.securetravels.crm.automation.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunStepRepository extends JpaRepository<WorkflowRunStep, UUID> {

    Optional<WorkflowRunStep> findByRunIdAndStepId(UUID runId, String stepId);

    long countByRunId(UUID runId);
}