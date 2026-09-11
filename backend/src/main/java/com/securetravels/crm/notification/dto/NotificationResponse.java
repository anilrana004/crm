package com.securetravels.crm.notification.dto;

import com.securetravels.crm.notification.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        Notification.Channel channel,
        String title,
        String body,
        String link,
        boolean read,
        Instant readAt,
        Instant createdAt
) {
}