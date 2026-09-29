package com.securetravels.crm.communications;

import com.securetravels.crm.common.audit.CreatedOnly;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A polymorphic communication record against a Lead, Customer360, or Booking
 * (Module 4).
 *
 * <p>Deliberately <strong>not</strong> a reuse of {@code audit_log}: that is a
 * mutation log whose actor comes from the SecurityContext, and its action enum
 * cannot express "the customer replied on WhatsApp" or "this template was read
 * at 14:02". Encoding that into a {@code field} name is how timelines become
 * unqueryable.
 *
 * <p>Rows are immutable, so the only write path is the static factories — each
 * one is a distinct, named thing that happened, which is what makes
 * {@code kind} worth having.
 */
@Entity
@Table(name = "timeline_events")
public class TimelineEvent extends CreatedOnly {

    public enum Direction { INBOUND, OUTBOUND }

    public enum Channel { WHATSAPP, EMAIL, SMS, SYSTEM }

    public enum Kind {
        TEMPLATE_QUEUED, TEMPLATE_SENT, TEMPLATE_DELIVERED, TEMPLATE_READ,
        TEMPLATE_OPENED, TEMPLATE_BOUNCED,
        TEMPLATE_FAILED, REPLY_RECEIVED, MEDIA_RECEIVED, BUTTON_CLICKED,
        SYSTEM_NOTE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel = Channel.WHATSAPP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Kind kind;

    @Column(name = "template_code", length = 60)
    private String templateCode;

    @Column(nullable = false, length = 300)
    private String summary;

    @Column(columnDefinition = "text")
    private String body;

    @Column(length = 30)
    private String provider;

    @Column(name = "provider_message_id", length = 80)
    private String providerMessageId;

    @Column(name = "customer_mobile", length = 20)
    private String customerMobile;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "body_values", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] bodyValues;

    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    protected TimelineEvent() {
    }

    private TimelineEvent(SubjectType subjectType, UUID subjectId, Direction direction,
                          Channel channel, Kind kind, String summary) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.direction = direction;
        this.channel = channel;
        this.kind = kind;
        this.summary = summary == null ? "" : (summary.length() <= 300 ? summary : summary.substring(0, 300));
    }

    public static TimelineEvent outboundTemplate(SubjectType subjectType, UUID subjectId, Kind kind,
                                                 String templateCode, String summary, String provider,
                                                 String providerMessageId, String customerMobile,
                                                 List<String> bodyValues) {
        TimelineEvent e = new TimelineEvent(subjectType, subjectId, Direction.OUTBOUND, Channel.WHATSAPP,
                kind, summary);
        e.templateCode = templateCode;
        e.provider = provider;
        e.providerMessageId = providerMessageId;
        e.customerMobile = customerMobile;
        e.bodyValues = bodyValues == null || bodyValues.isEmpty() ? null : bodyValues.toArray(new String[0]);
        return e;
    }

    public static TimelineEvent inbound(SubjectType subjectType, UUID subjectId, Channel channel, Kind kind,
                                       String summary, String body, String provider,
                                       String providerMessageId, String customerMobile) {
        TimelineEvent e = new TimelineEvent(subjectType, subjectId, Direction.INBOUND, channel,
                kind, summary);
        e.body = body;
        e.provider = provider;
        e.providerMessageId = providerMessageId;
        e.customerMobile = customerMobile;
        return e;
    }

    public static TimelineEvent systemNote(SubjectType subjectType, UUID subjectId, String summary,
                                           UUID actorId) {
        TimelineEvent e = new TimelineEvent(subjectType, subjectId, Direction.INBOUND, Channel.SYSTEM,
                Kind.SYSTEM_NOTE, summary);
        e.actorId = actorId;
        return e;
    }

    public UUID getId() { return id; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public Direction getDirection() { return direction; }
    public Channel getChannel() { return channel; }
    public Kind getKind() { return kind; }
    public String getTemplateCode() { return templateCode; }
    public String getSummary() { return summary; }
    public String getBody() { return body; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getCustomerMobile() { return customerMobile; }
    public UUID getActorId() { return actorId; }
    public Long getSeq() { return seq; }

    public List<String> bodyValues() {
        return bodyValues == null ? List.of() : Arrays.asList(bodyValues);
    }
}
