package com.securetravels.crm.lead;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditLogRepository;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.dto.LeadCreateRequest;
import com.securetravels.crm.lead.dto.LeadResponse;
import com.securetravels.crm.lead.dto.LeadStatusRequest;
import com.securetravels.crm.task.FollowUpAutomation;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    @Mock private LeadRepository leads;
    @Mock private UserRepository users;
    @Mock private Customer360Repository customers;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AuditService auditService;
    @Mock private LeadScoringService scoringService;
    @Mock private FollowUpAutomation automation;
    @Mock private com.securetravels.crm.webhook.RoundRobinService roundRobin;

    private LeadService service;
    private UUID principalId;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new LeadService(leads, users, customers, auditLogRepository, auditService,
                scoringService, automation, roundRobin);
        principalId = UUID.randomUUID();
        principal = new UserPrincipal(principalId, "sales@securetravels.in", "Ravi", Role.SALES, true);
    }

    private LeadCreateRequest request(Boolean consent) {
        return new LeadCreateRequest(
                "Amit Verma", "9876500001", "9876500001", "amit@example.com", Lead.Source.WHATSAPP,
                "Kedarnath", null, LocalDate.now().plusDays(100), 2, new BigDecimal("15000"),
                null, null, "call after noon", consent, null);
    }

    @Test
    void createRequiresExplicitConsent() {
        assertThatThrownBy(() -> service.create(request(false), principal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Explicit consent");
        verify(leads, never()).save(any());
    }

    @Test
    void createScoresLeadAndKicksOffAutomation() throws Exception {
        when(leads.findFirstActiveDuplicate("9876500001")).thenReturn(Optional.empty());
        when(customers.findByMobileDigits("9876500001")).thenReturn(Optional.empty());
        when(leads.save(any(Lead.class))).thenAnswer(inv -> {
            Lead lead = inv.getArgument(0);
            setLeadId(lead, UUID.randomUUID());
            return lead;
        });
        when(scoringService.score(any(Lead.class))).thenReturn(Lead.Heat.HOT);
        when(users.findById(any())).thenReturn(Optional.empty());

        LeadResponse response = service.create(request(true), principal);

        assertThat(response.heat()).isEqualTo(Lead.Heat.HOT);
        assertThat(response.customerName()).isEqualTo("Amit Verma");
        verify(scoringService).score(any(Lead.class));
        verify(automation).onLeadCreated(any(), eq(principalId));
        verify(auditService).record(eq("LEAD"), any(), eq(AuditAction.CREATE), eq("lead"), any(), any());
    }

    @Test
    void lostWithoutReasonIsRejected() {
        Lead lead = lead(Lead.Status.INTERESTED, principalId);
        when(leads.findById(lead.getId())).thenReturn(Optional.of(lead));

        assertThatThrownBy(() -> service.updateStatus(lead.getId(),
                new LeadStatusRequest(Lead.Status.LOST, null, null), principal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("lostReason");
        assertThat(lead.getStatus()).isEqualTo(Lead.Status.INTERESTED);
    }

    @Test
    void lostWithReasonMarksColdAndAuditsReason() {
        Lead lead = lead(Lead.Status.INTERESTED, principalId);
        when(leads.findById(lead.getId())).thenReturn(Optional.of(lead));
        when(leads.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(any())).thenReturn(Optional.empty());

        LeadResponse response = service.updateStatus(lead.getId(),
                new LeadStatusRequest(Lead.Status.LOST, Lead.LostReason.PRICE_TOO_HIGH, "quoted 30k over"),
                principal);

        assertThat(response.status()).isEqualTo(Lead.Status.LOST);
        assertThat(response.heat()).isEqualTo(Lead.Heat.COLD);
        assertThat(response.lostReason()).isEqualTo(Lead.LostReason.PRICE_TOO_HIGH);
        verify(leads).save(lead);
        verify(auditService).statusChange("LEAD", lead.getId(), "lost_reason", null, "PRICE_TOO_HIGH");
    }

    @Test
    void invalidTransitionIsRejected() {
        Lead lead = lead(Lead.Status.NEW, principalId);
        when(leads.findById(lead.getId())).thenReturn(Optional.of(lead));

        assertThatThrownBy(() -> service.updateStatus(lead.getId(),
                new LeadStatusRequest(Lead.Status.BOOKING_CONFIRMED, null, null), principal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    void salesCannotUpdateALeadTheyDoNotOwn() {
        Lead lead = lead(Lead.Status.INTERESTED, UUID.randomUUID());
        when(leads.findById(lead.getId())).thenReturn(Optional.of(lead));

        assertThatThrownBy(() -> service.updateStatus(lead.getId(),
                new LeadStatusRequest(Lead.Status.QUOTATION_SENT, null, null), principal))
                .isInstanceOf(ForbiddenException.class);
    }

    private Lead lead(Lead.Status status, UUID ownerId) {
        Lead lead = new Lead();
        lead.setStatus(status);
        lead.setOwnerId(ownerId);
        return lead;
    }

    private static void setLeadId(Lead lead, UUID id) throws Exception {
        java.lang.reflect.Field field = Lead.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(lead, id);
    }
}