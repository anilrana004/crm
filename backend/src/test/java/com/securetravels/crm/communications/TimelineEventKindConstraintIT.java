package com.securetravels.crm.communications;

import com.securetravels.crm.common.util.PhoneUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database's {@code timeline_events_kind_check} constraint has to name
 * every constant the {@link TimelineEvent.Kind} enum names, and that pairing
 * has no compiler or IDE support whatsoever. Adding an enum constant is a
 * one-word change; forgetting the migration is invisible until the first row
 * carrying that kind fails to insert, at runtime, in production.
 *
 * <p>This class is that pairing made executable. It is a metadata assertion
 * rather than a round-trip insert on purpose: inserting all eleven kinds needs
 * a populated timeline's foreign keys, whereas reading the constraint proves
 * the same thing in one query and cannot be made to pass by accident.
 *
 * <p>It is not a substitute for a test that actually writes each kind, but it
 * is the check that would have caught the omission immediately.
 */
@SpringBootTest
@ActiveProfiles("test")
class TimelineEventKindConstraintIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("every Kind constant is accepted by the timeline_events_kind_check constraint")
    void everyKindIsAllowedByTheDatabase() {
        String definition = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'timeline_events_kind_check'
                """, String.class);

        assertThat(definition)
                .as("the constraint must exist; if this fails, a migration dropped it")
                .isNotNull();

        List<String> allowed = Arrays.stream(TimelineEvent.Kind.values())
                .map(Enum::name)
                .toList();

        assertThat(allowed)
                .as("the enum must keep a stable set of kinds")
                .contains("TEMPLATE_FAILED", "TEMPLATE_OPENED", "TEMPLATE_BOUNCED");

        for (String kind : allowed) {
            assertThat(definition)
                    .as("Kind.%s is missing from timeline_events_kind_check, so writing it would fail at runtime",
                            kind)
                    .contains("'" + kind + "'");
        }
    }

    @Test
    @DisplayName("a suppression must name exactly one subject, and the right column for its channel")
    void suppressionIdentityIsExclusive() {
        // An EMAIL row with a NULL address is an opt-out that suppresses nobody,
        // which is worse than no opt-out at all: the business believes a
        // suppression exists and sends anyway.
        String constraint = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'ck_suppression_identity'
                """, String.class);

        assertThat(constraint).isNotNull();
        assertThat(constraint).contains("email_address IS NOT NULL");
        assertThat(constraint).contains("mobile_digits IS NULL");
        assertThat(constraint).contains("mobile_digits IS NOT NULL");
        assertThat(constraint).contains("email_address IS NULL");
    }

    @Test
    @DisplayName("Phase 1 stores mobiles as ten digits with no country prefix")
    void mobileNormalisationIsUnambiguous() {
        // The inbound lead path and every existing lead lookup both key off
        // PhoneUtils.normalize, so a lead created by a WhatsApp stranger has to
        // land on the same digits as one created through the CRM. If this ever
        // changes, dedup silently stops matching and duplicate leads appear.
        assertThat(PhoneUtils.normalize("9000000000")).isEqualTo("9000000000");
        assertThat(PhoneUtils.normalize("+91 90000 00000")).isEqualTo("9000000000");
        assertThat(PhoneUtils.normalize("919000000000")).isEqualTo("9000000000");
        assertThat(PhoneUtils.normalize("5900000000")).as("not a valid Indian mobile").isNull();
    }
}
