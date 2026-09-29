package com.securetravels.crm.communications.consent;

import com.securetravels.crm.communications.TimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConsentSuppressionRepository extends JpaRepository<ConsentSuppression, UUID> {

    Optional<ConsentSuppression> findByMobileDigitsAndChannel(String mobileDigits,
                                                              TimelineEvent.Channel channel);

    /**
     * Case-insensitive address lookup. Written as an explicit query because
     * Spring Data has no {@code ...AndChannelIgnoreCase} keyword — the derived
     * name parses as a property called "channelignorecase" and fails at
     * context startup. The lower-casing is done in the parameter, for the same
     * reason {@code Customer360Repository} prepares its search pattern in Java:
     * PostgreSQL types an untyped null parameter as bytea and rejects
     * {@code lower(bytea)}.
     */
    @Query("""
            select s from ConsentSuppression s
             where lower(s.emailAddress) = lower(:email) and s.channel = :channel
            """)
    Optional<ConsentSuppression> findByEmailAddress(@Param("email") String email,
                                                    @Param("channel") TimelineEvent.Channel channel);
}