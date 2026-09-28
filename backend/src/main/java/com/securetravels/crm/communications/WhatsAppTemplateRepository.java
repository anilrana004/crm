package com.securetravels.crm.communications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, String> {

    Optional<WhatsAppTemplate> findByCodeAndEnabledTrue(String code);
}
