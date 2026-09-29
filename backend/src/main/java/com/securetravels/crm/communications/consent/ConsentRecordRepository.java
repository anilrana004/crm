package com.securetravels.crm.communications.consent;

import com.securetravels.crm.communications.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    Optional<ConsentRecord> findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
            UUID customerId, TimelineEvent.Channel channel, Purpose purpose);
}