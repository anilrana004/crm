package com.securetravels.crm.automation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Optional<Workflow> findBySlug(String slug);
}