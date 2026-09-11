package com.securetravels.crm.task;

import com.securetravels.crm.common.notify.EmailNotifier;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowUpAutomationTest {

    @Mock private TaskRepository tasks;
    @Mock private UserRepository users;
    @Mock private NotificationRepository notifications;
    @Mock private EmailNotifier email;

    private FollowUpAutomation automation;
    private UUID assignee;
    private User user;

    @BeforeEach
    void setUp() {
        automation = new FollowUpAutomation(tasks, users, notifications, email);
        assignee = UUID.randomUUID();
        user = new User("sales@securetravels.in", "pw", "Ravi", Role.SALES, "9876500000");
    }

    @Test
    void leadCreatedSchedulesInitialCallAtPlusFiveMinutes() {
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(assignee)).thenReturn(Optional.of(user));

        Instant now = Instant.now();
        automation.onLeadCreated(UUID.randomUUID(), assignee);

        Task t = captureSavedTasks(1).get(0);
        assertThat(t.getType()).isEqualTo(Task.Type.INITIAL_CALL);
        assertThat(t.getStatus()).isEqualTo(Task.Status.PENDING);
        assertThat(t.getAssigneeId()).isEqualTo(assignee);
        assertThat(t.getDueAt()).isAfter(now.plus(4, ChronoUnit.MINUTES));
        assertThat(t.getDueAt()).isBefore(now.plus(6, ChronoUnit.MINUTES));
        assertThat(t.getSlaDeadline()).isEqualTo(t.getDueAt().plus(1, ChronoUnit.HOURS));

        verify(notifications, times(1)).save(any(Notification.class));
        verify(email).send(eq(user.getEmail()), eq("Call within 5 minutes"), anyString());
    }

    @Test
    void interestedSchedulesFollowUpsAtOneThreeEightFifteenDaySpacing() {
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(assignee)).thenReturn(Optional.of(user));

        Lead lead = new Lead();
        lead.setOwnerId(assignee);
        lead.setCustomerName("Amit");

        automation.onInterested(lead);

        List<Task> saved = captureSavedTasks(4);
        assertThat(saved).extracting(Task::getType)
                .containsExactly(Task.Type.FOLLOW_UP_1D, Task.Type.FOLLOW_UP_3D,
                        Task.Type.FOLLOW_UP_8D, Task.Type.FOLLOW_UP_15D);
        // Cumulative offsets from lead creation (base = now when createdAt is absent):
        // +1, +3 (2 days after FU1), +8 (5 days after FU2), +15 (7 days after FU3).
        assertThat(ChronoUnit.DAYS.between(saved.get(0).getDueAt(), saved.get(1).getDueAt())).isEqualTo(2);
        assertThat(ChronoUnit.DAYS.between(saved.get(1).getDueAt(), saved.get(2).getDueAt())).isEqualTo(5);
        assertThat(ChronoUnit.DAYS.between(saved.get(2).getDueAt(), saved.get(3).getDueAt())).isEqualTo(7);
        for (Task t : saved) {
            assertThat(t.getStatus()).isEqualTo(Task.Status.PENDING);
            assertThat(t.getAssigneeId()).isEqualTo(assignee);
            assertThat(t.getSlaDeadline()).isEqualTo(t.getDueAt().plus(24, ChronoUnit.HOURS));
        }
        verify(notifications, times(4)).save(any(Notification.class));
    }

    @Test
    void reEnteringInterestedCancelsOldOpenFollowUpsFirst() {
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(assignee)).thenReturn(Optional.of(user));
        Task old1d = new Task(UUID.randomUUID(), assignee, Task.Type.FOLLOW_UP_1D,
                Instant.now(), Instant.now().plus(24, ChronoUnit.HOURS));
        Task old15d = new Task(UUID.randomUUID(), assignee, Task.Type.FOLLOW_UP_15D,
                Instant.now(), Instant.now().plus(24, ChronoUnit.HOURS));
        when(tasks.findByLeadIdAndTypeAndStatusIn(any(), any(), anyList()))
                .thenReturn(List.of(old1d, old15d));

        Lead lead = new Lead();
        lead.setOwnerId(assignee);
        lead.setCustomerName("Amit");

        automation.onInterested(lead);

        assertThat(old1d.getStatus()).isEqualTo(Task.Status.CANCELLED);
        assertThat(old15d.getStatus()).isEqualTo(Task.Status.CANCELLED);
        verify(tasks, times(4)).save(any(Task.class));
    }

    @Test
    void quotationSentSchedulesQuotationAtPlusTwoDays() {
        when(tasks.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(assignee)).thenReturn(Optional.of(user));

        Lead lead = new Lead();
        lead.setOwnerId(assignee);
        lead.setCustomerName("Meera");

        automation.onQuotationSent(lead);

        Task t = captureSavedTasks(1).get(0);
        assertThat(t.getType()).isEqualTo(Task.Type.QUOTATION);
        assertThat(t.getStatus()).isEqualTo(Task.Status.PENDING);
        assertThat(t.getSlaDeadline()).isEqualTo(t.getDueAt().plus(24, ChronoUnit.HOURS));
        assertThat(t.getDueAt()).isAfter(Instant.now());
        assertThat(t.getDueAt()).isBefore(Instant.now().plus(3, ChronoUnit.DAYS));
    }

    @Test
    void bookingConfirmationCancelsAllOutstandingAndNotifies() {
        Task open = new Task(UUID.randomUUID(), assignee, Task.Type.FOLLOW_UP_3D,
                Instant.now(), Instant.now().plus(24, ChronoUnit.HOURS));
        when(tasks.findByLeadIdAndStatusIn(any(), anyList())).thenReturn(List.of(open));

        Lead lead = new Lead();
        lead.setOwnerId(assignee);
        lead.setCustomerName("Amit");

        automation.onBooked(lead);

        assertThat(open.getStatus()).isEqualTo(Task.Status.CANCELLED);
        verify(tasks, never()).save(any(Task.class));
        verify(notifications).save(any(Notification.class));
    }

    private List<Task> captureSavedTasks(int expected) {
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(tasks, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }
}