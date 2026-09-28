package com.securetravels.crm.communications.dto;

import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.TimelineEvent;

import java.time.Instant;
import java.util.UUID;

public record TimelineResponse(
        Long seq,
        SubjectType subjectType,
        UUID subjectId,
        TimelineEvent.Direction direction,
        TimelineEvent.Channel channel,
        TimelineEvent.Kind kind,
        String templateCode,
        String summary,
        String body,
        String provider,
        String customerMobile,
        Instant createdAt) {

    public static TimelineResponse from(TimelineEvent e) {
        return new TimelineResponse(e.getSeq(), e.getSubjectType(), e.getSubjectId(), e.getDirection(),
                e.getChannel(), e.getKind(), e.getTemplateCode(), e.getSummary(), e.getBody(),
                e.getProvider(), e.getCustomerMobile(), e.getCreatedAt());
    }
}
