package com.securetravels.crm.notification;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.notification.dto.NotificationResponse;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "My notification feed (newest first)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                    @CurrentUser UserPrincipal caller) {
        return Map.of("items", notificationService.list(unreadOnly, caller),
                "unread", notificationService.unreadCount(caller));
    }

    @Operation(summary = "Mark one notification read", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/read", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public NotificationResponse markRead(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return notificationService.markRead(id, caller);
    }

    @Operation(summary = "Mark all my notifications read", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping(path = "/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public Map<String, Integer> markAllRead(@CurrentUser UserPrincipal caller) {
        return Map.of("updated", notificationService.markAllRead(caller));
    }
}