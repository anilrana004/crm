package com.securetravels.crm.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookLogRepository extends JpaRepository<WebhookLog, UUID> {
}