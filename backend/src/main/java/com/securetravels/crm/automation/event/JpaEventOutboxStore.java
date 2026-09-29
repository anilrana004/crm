package com.securetravels.crm.automation.event;

import org.springframework.stereotype.Component;

@Component
public class JpaEventOutboxStore implements EventOutboxStore {

    private final AutomationEventRecordRepository repository;

    public JpaEventOutboxStore(AutomationEventRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(AutomationEventRecord record) {
        repository.save(record);
    }
}