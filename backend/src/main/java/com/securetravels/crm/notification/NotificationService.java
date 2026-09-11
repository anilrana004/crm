package com.securetravels.crm.notification;

import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.notification.dto.NotificationResponse;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(boolean unreadOnly, UserPrincipal caller) {
        List<Notification> rows = unreadOnly
                ? notifications.findTop100ByUserIdAndReadFalseOrderByCreatedAtDesc(caller.id())
                : notifications.findTop50ByUserIdOrderByCreatedAtDesc(caller.id());
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UserPrincipal caller) {
        return notifications.countByUserIdAndReadFalse(caller.id());
    }

    @Transactional
    public NotificationResponse markRead(UUID id, UserPrincipal caller) {
        Notification n = notifications.findByIdAndUserId(id, caller.id())
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        if (!n.isRead()) {
            n.markRead(Instant.now());
        }
        return toResponse(n);
    }

    @Transactional
    public int markAllRead(UserPrincipal caller) {
        List<Notification> unread = notifications.findTop100ByUserIdAndReadFalseOrderByCreatedAtDesc(caller.id());
        Instant now = Instant.now();
        unread.forEach(n -> { if (!n.isRead()) n.markRead(now); });
        return unread.size();
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getChannel(), n.getTitle(), n.getBody(),
                n.getLink(), n.isRead(), n.getReadAt(), n.getCreatedAt());
    }
}