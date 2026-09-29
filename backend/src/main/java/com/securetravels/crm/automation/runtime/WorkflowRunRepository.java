package com.securetravels.crm.automation.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    List<WorkflowRun> findByWorkflowIdAndStatusIn(UUID workflowId, Set<WorkflowRunStatus> statuses);

    List<WorkflowRun> findAllByStatusIn(Set<WorkflowRunStatus> statuses);
}