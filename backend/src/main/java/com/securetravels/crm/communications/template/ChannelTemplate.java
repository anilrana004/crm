package com.securetravels.crm.communications.template;

import com.securetravels.crm.common.audit.CreatedUpdated;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * The cross-channel template catalogue (Phase 5 Module 2).
 *
 * <p>WhatsApp templates predate this table and keep their own mirror (V12)
 * because their {@code interakt_name} is provider-side metadata. This table
 * exists so one screen lists every channel, and so email/SMS templates have the
 * same category + approval policy WhatsApp gained in V14 — without which a
 * promotion could be emailed with no consent check.
 *
 * <p>{@code approvalStatus} defaults to {@code PENDING}: an email or SMS
 * template is not usable until a human has confirmed the wording and, for SMS,
 * the DLT registration. A template that is {@code PENDING} or {@code REJECTED}
 * is rejected by the dispatcher, exactly like WhatsApp.
 */
@Entity
@Table(name = "channel_templates")
public class ChannelTemplate extends CreatedUpdated {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommunicationChannel channel;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode = "en";

    @Column(name = "subject_line", length = 300)
    private String subjectLine;

    @Column(name = "body")
    private String body;

    @Column(name = "expected_params", nullable = false)
    private int expectedParams = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Purpose category = Purpose.TRANSACTIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "provider_ref", length = 120)
    private String providerRef;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }

    public static ChannelTemplate of(CommunicationChannel channel, String code, String label,
                                     Purpose category, int expectedParams, ApprovalStatus approval) {
        ChannelTemplate t = new ChannelTemplate();
        t.channel = channel;
        t.code = code;
        t.label = label;
        t.category = category;
        t.expectedParams = expectedParams;
        t.approvalStatus = approval;
        return t;
    }

    public void setBody(String body) { this.body = body; }
    public void setSubjectLine(String subjectLine) { this.subjectLine = subjectLine; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }
    public void setApprovalStatus(ApprovalStatus approvalStatus) { this.approvalStatus = approvalStatus; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public UUID getId() { return id; }
    public CommunicationChannel getChannel() { return channel; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getLanguageCode() { return languageCode; }
    public String getSubjectLine() { return subjectLine; }
    public String getBody() { return body; }
    public int getExpectedParams() { return expectedParams; }
    public Purpose getCategory() { return category; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public String getProviderRef() { return providerRef; }
    public boolean isEnabled() { return enabled; }
}
