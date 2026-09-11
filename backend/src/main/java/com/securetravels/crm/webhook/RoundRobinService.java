package com.securetravels.crm.webhook;

import com.securetravels.crm.common.exception.ServiceUnavailableException;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Round-robin assignment for auto-provisioned leads (Module 9). The next
 * owner is the active SALES user with the oldest assignment in the current
 * month (least-recently-assigned); deterministic tie-break by created_at
 * then id. Persists a per-month cursor for rotation. Falls back to the
 * first active MANAGER when no SALES user is available.
 *
 * Note: not concurrency-safe under simultaneous webhook bursts — phase 1
 * tolerance, mirroring the legacy prototype.
 */
@Service
public class RoundRobinService {

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public RoundRobinService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public User pickNextSalesUser() {
        LocalDate month = LocalDate.now()
                .withDayOfMonth(1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id FROM users u
                LEFT JOIN assignment_state s
                       ON s.user_id = u.id AND s.month = CAST(? AS date)
                WHERE u.role = 'SALES' AND u.is_active = true
                ORDER BY COALESCE(s.last_assigned_at, to_timestamp(0)) ASC,
                         u.created_at ASC, u.id ASC
                LIMIT 1
                """, month);

        if (!rows.isEmpty()) {
            UUID id = (UUID) rows.get(0).get("id");
            upsertAssignment(id, month);
            return users.findById(id).orElse(null);
        }

        User manager = users.findFirstByRoleOrderByCreatedAtAsc(Role.MANAGER)
                .filter(User::isActive)
                .orElse(null);
        if (manager == null) {
            throw new ServiceUnavailableException("No sales or manager user is available to own this lead.");
        }
        return manager;
    }

    private void upsertAssignment(UUID userId, LocalDate month) {
        jdbc.update("""
                INSERT INTO assignment_state (user_id, month, last_assigned_at, leads_assigned_this_month)
                VALUES (?, CAST(? AS date), now(), 1)
                ON CONFLICT (user_id, month)
                DO UPDATE SET last_assigned_at = now(),
                              leads_assigned_this_month = assignment_state.leads_assigned_this_month + 1
                """, userId, month);
    }
}