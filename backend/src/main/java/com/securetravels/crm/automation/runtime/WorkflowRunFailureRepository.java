package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.automation.runtime.WorkflowRunFailure.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowRunFailureRepository extends JpaRepository<WorkflowRunFailure, UUID> {

    List<WorkflowRunFailure> findByStatusOrderByCreatedAtDesc(Status status);
}