package com.securetravels.crm.task;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskAutomationIT extends BaseIT {

    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private FollowUpSweep followUpSweep;

    @Test
    void leadCreationSchedulesInitialCallTaskAndNotifiesOwner() throws Exception {
        UUID sales = createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        String id = createLead(token, "New Customer", "9876500101");

        Task task = taskRepository.findByLeadIdOrderByDueAtAsc(UUID.fromString(id)).get(0);
        assertThat(task.getType()).isEqualTo(Task.Type.INITIAL_CALL);
        assertThat(task.getStatus()).isEqualTo(Task.Status.PENDING);
        assertThat(task.getAssigneeId()).isEqualTo(sales);
        assertThat(task.getDueAt()).isBetween(Instant.now().minusSeconds(5),
                Instant.now().plus(7, ChronoUnit.MINUTES));
        assertThat(task.getSlaDeadline()).isEqualTo(task.getDueAt().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/tasks").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("INITIAL_CALL"))
                .andExpect(jsonPath("$[0].customerName").value("New Customer"));

        assertThat(notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(sales))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getChannel()).isEqualTo(Notification.Channel.IN_APP);
                    assertThat(n.getTitle()).isEqualTo("Call within 5 minutes");
                    assertThat(n.getLink()).isEqualTo("/leads/" + id);
                });
    }

    @Test
    void interestedSchedulesFollowUpCadence() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, "Cadence Lead", "9876500102");

        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INTERESTED"))))
                .andExpect(status().isOk());

        var open = taskRepository.findByLeadIdOrderByDueAtAsc(UUID.fromString(id)).stream()
                .filter(t -> t.getStatus() == Task.Status.PENDING).toList();
        assertThat(open).hasSize(5)
                .extracting(Task::getType)
                .contains(Task.Type.INITIAL_CALL, Task.Type.FOLLOW_UP_1D, Task.Type.FOLLOW_UP_3D,
                        Task.Type.FOLLOW_UP_8D, Task.Type.FOLLOW_UP_15D);

        Lead lead = leadRepository.findById(UUID.fromString(id)).orElseThrow();
        for (Task t : open) {
            long expected = switch (t.getType()) {
                case FOLLOW_UP_1D -> 1;
                case FOLLOW_UP_3D -> 3;
                case FOLLOW_UP_8D -> 8;
                case FOLLOW_UP_15D -> 15;
                default -> -1;
            };
            if (expected > 0) {
                assertThat(t.getDueAt())
                        .isCloseTo(lead.getCreatedAt().plus(expected, ChronoUnit.DAYS), within(90, ChronoUnit.SECONDS));
            }
        }
    }

    @Test
    void quotationSentSchedulesQuotationFollowUp() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, "Quote Lead", "9876500103");

        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "QUOTATION_SENT"))))
                .andExpect(status().isOk());

        var quotations = taskRepository.findByLeadIdAndTypeAndStatusIn(UUID.fromString(id),
                Task.Type.QUOTATION, java.util.List.of(Task.Status.PENDING));
        assertThat(quotations).hasSize(1);
        assertThat(quotations.get(0).getDueAt())
                .isCloseTo(leadRepository.findById(UUID.fromString(id)).orElseThrow()
                        .getCreatedAt().plus(2, ChronoUnit.DAYS), within(90, ChronoUnit.SECONDS));
    }

    @Test
    void revisitingInterestedNeverDuplicatesCadence() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, "Idempotent Lead", "9876500104");

        // INTERESTED -> QUOTATION_SENT -> back to INTERESTED re-triggers onInterested,
        // but must not accumulate duplicate cadence tasks.
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INTERESTED"))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "QUOTATION_SENT"))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INTERESTED"))))
                .andExpect(status().isOk());

        long scheduled = taskRepository.findAll().stream()
                .filter(t -> t.getLeadId().equals(UUID.fromString(id)))
                .filter(t -> t.getType() == Task.Type.FOLLOW_UP_1D || t.getType() == Task.Type.FOLLOW_UP_3D
                        || t.getType() == Task.Type.FOLLOW_UP_8D || t.getType() == Task.Type.FOLLOW_UP_15D)
                .filter(t -> t.getStatus() == Task.Status.PENDING)
                .count();
        assertThat(scheduled).isEqualTo(4);
    }

    @Test
    void completeTaskEnforcesAssignee() throws Exception {
        UUID ravi = createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");
        createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");
        String raviToken = login("sales.ravi@securetravels.in", "sales123");
        String meeraToken = login("sales.meera@securetravels.in", "sales123");

        String id = createLead(raviToken, "Completion Lead", "9876500105");
        Task task = taskRepository.findByLeadIdOrderByDueAtAsc(UUID.fromString(id)).get(0);
        assertThat(task.getAssigneeId()).isEqualTo(ravi);

        mockMvc.perform(patch("/api/tasks/" + task.getId() + "/complete")
                        .header("Authorization", authHeader(meeraToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/tasks/" + task.getId() + "/complete")
                        .header("Authorization", authHeader(raviToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(Task.Status.COMPLETED);
    }

    @Test
    void overdueAndSlaEscalationHandledBySweep() throws Exception {
        UUID sales = createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        UUID manager = createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, "Sweep Lead", "9876500106");

        // Force the task into the past so the sweep acts on it.
        Task past = taskRepository.findByLeadIdOrderByDueAtAsc(UUID.fromString(id)).get(0);
        jdbcTemplate.update("UPDATE tasks SET status='PENDING', due_at = now() - interval '2 hours', "
                + "sla_deadline = now() - interval '1 hour' WHERE id = ?", past.getId());

        // A second past-SLA task still PENDING (PENDING + past due + past sla).
        Task pending = new Task(UUID.fromString(id), sales, Task.Type.QUOTATION,
                Instant.now().minus(3, ChronoUnit.HOURS), Instant.now().minus(2, ChronoUnit.HOURS));
        taskRepository.save(pending);

        followUpSweep.runSweep();

        assertThat(taskRepository.findById(past.getId()).orElseThrow().getEscalatedAt()).isNotNull();
        assertThat(taskRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(Task.Status.OVERDUE);
        assertThat(taskRepository.findById(pending.getId()).orElseThrow().getEscalatedAt()).isNotNull();

        assertThat(notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(manager))
                .anyMatch(n -> n.getBody().contains("SLA deadline missed"));
    }

    @Test
    void notificationsMarkReadFlowAndUnreadCount() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        createLead(token, "Notif Lead", "9876500107");

        mockMvc.perform(get("/api/notifications").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Call within 5 minutes"));

        UUID notifId = notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(
                userRepository.findByEmailIgnoreCase("sales@securetravels.in").orElseThrow().getId()).get(0).getId();

        mockMvc.perform(patch("/api/notifications/" + notifId + "/read")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/notifications").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    private String createLead(String token, String name, String mobile) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customerName", name);
        map.put("mobileNumber", mobile);
        map.put("whatsappNumber", mobile);
        map.put("source", "WEBSITE");
        map.put("destination", "Kedarnath");
        map.put("numPersons", 2);
        map.put("budget", 15000);
        map.put("travelDate", "2026-12-24");
        map.put("consentGiven", true);
        String body = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(map)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}