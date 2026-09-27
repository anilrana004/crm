package com.securetravels.crm.common.exception;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request to a path with no controller and no static resource is a 404, not
 * a 500. Regression guard: both Spring exceptions
 * ({@code NoResourceFoundException} from the static-resource handler,
 * {@code NoHandlerFoundException} when nothing resolves) previously fell
 * through to the catch-all {@code Exception} handler and were reported as
 * INTERNAL_ERROR, which sent callers hunting for server faults.
 *
 * <p>Requests are authenticated because {@code .anyRequest().authenticated()}
 * rejects anonymous callers with 401 before routing ever happens — that
 * ordering is itself the correct behaviour, so asserting 404 requires a token.
 */
class NotFoundRouteIT extends BaseIT {

    private String adminToken;

    private void givenAuthenticatedAdmin() throws Exception {
        if (adminToken == null) {
            createUser("admin@notfound.test", "Admin User", Role.ADMIN, "admin123");
            adminToken = login("admin@notfound.test", "admin123");
        }
    }

    @Test
    @DisplayName("GET /api/does-not-exist returns 404 NOT_FOUND, not 500")
    void unknownApiRouteIs404() throws Exception {
        givenAuthenticatedAdmin();
        mockMvc.perform(get("/api/does-not-exist").header("Authorization", authHeader(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("GET /totally/unmapped/path (no /api prefix) also returns 404")
    void unknownRootRouteIs404() throws Exception {
        givenAuthenticatedAdmin();
        mockMvc.perform(get("/totally/unmapped/path").header("Authorization", authHeader(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("unknown sub-resource under a real prefix returns 404")
    void unknownSubResourceIs404() throws Exception {
        givenAuthenticatedAdmin();
        mockMvc.perform(get("/api/leads/00000000-0000-0000-0000-000000000000/nope")
                        .header("Authorization", authHeader(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("anonymous caller still gets 401 — security runs before routing")
    void anonymousStillGets401() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }
}
