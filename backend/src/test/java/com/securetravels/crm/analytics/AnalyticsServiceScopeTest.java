package com.securetravels.crm.analytics;

import com.securetravels.crm.analytics.dto.ReportFilter;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Role scoping is the one place Module 2 makes a security decision, and it is
 * made in the service rather than in @PreAuthorize. These tests pin that
 * behaviour, because the failure mode is silent: remove the scoping and every
 * report still returns data, just somebody else's.
 */
@SpringBootTest
@WithMockUser
class AnalyticsServiceScopeTest {

    @Autowired
    private AnalyticsService service;

    private static UserPrincipal caller(UUID id, Role role) {
        return new UserPrincipal(id, "x@y.z", "X", role, true);
    }

    @Test
    @DisplayName("a SALES caller cannot widen scope by passing another consultantId")
    void salesIsForcedToSelf() {
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        ReportFilter requested = new ReportFilter(someoneElse, null, null, null, null, null);
        ReportFilter scoped = service.scopeToCaller(requested, caller(me, Role.SALES));

        assertThat(scoped.consultantId())
                .as("a SALES user asking for a colleague's report must be silently redirected to self")
                .isEqualTo(me);
    }

    @Test
    @DisplayName("a SALES caller with no filter is still scoped to self")
    void salesWithNoFilterIsScoped() {
        UUID me = UUID.randomUUID();
        ReportFilter scoped = service.scopeToCaller(ReportFilter.none(), caller(me, Role.SALES));
        assertThat(scoped.consultantId()).isEqualTo(me);
    }

    @Test
    @DisplayName("manager, admin, CEO and OPS may request any consultant")
    void privilegedRolesPassThrough() {
        UUID target = UUID.randomUUID();
        ReportFilter requested = new ReportFilter(target, null, null, null, null, null);

        for (Role role : new Role[]{Role.MANAGER, Role.ADMIN, Role.CEO, Role.OPS}) {
            assertThat(service.scopeToCaller(requested, caller(UUID.randomUUID(), role)).consultantId())
                    .as("%s should keep the requested consultant", role)
                    .isEqualTo(target);
        }
    }

    @Test
    @DisplayName("a privileged role asking for nobody still sees everybody")
    void privilegedRoleWithNoFilterStaysUnscoped() {
        ReportFilter scoped = service.scopeToCaller(
                ReportFilter.none(), caller(UUID.randomUUID(), Role.MANAGER));
        assertThat(scoped.consultantId()).isNull();
    }

    @Test
    @DisplayName("scoping preserves every other filter field")
    void scopingDoesNotDisturbOtherFields() {
        UUID me = UUID.randomUUID();
        UUID trip = UUID.randomUUID();

        ReportFilter scoped = service.scopeToCaller(
                new ReportFilter(null, trip, java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 3, 1), Season.MONSOON, "instagram"),
                caller(me, Role.SALES));

        assertThat(scoped.tripId()).isEqualTo(trip);
        assertThat(scoped.from()).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(scoped.to()).isEqualTo(java.time.LocalDate.of(2026, 3, 1));
        assertThat(scoped.season()).isEqualTo(Season.MONSOON);
        assertThat(scoped.source()).isEqualTo("instagram");
    }
}
