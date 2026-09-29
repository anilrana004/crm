package com.securetravels.crm.automation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

    Optional<WorkflowVersion> findByWorkflowIdAndVersionNumber(UUID workflowId, int versionNumber);

    List<WorkflowVersion> findByWorkflowIdOrderByVersionNumberDesc(UUID workflowId);

    Optional<WorkflowVersion> findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(
            UUID workflowId, WorkflowStatus status);

    List<WorkflowVersion> findByStatus(WorkflowStatus status);

    /** The engine's feed: every runnable definition, newest version per workflow. */
    List<WorkflowVersion> findByStatusOrderByCreatedAtAsc(WorkflowStatus status);
}