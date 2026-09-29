package com.securetravels.crm.communications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.securetravels.crm.common.audit.CreatedUpdated;

/**
 * Our mirror of an Interakt/Meta approved template (Module 4).
 *
 * <p>Interakt has <strong>no template list or create API</strong> — templates
 * are created in the Interakt dashboard or synced from Meta Business Manager,
 * and the code name is only visible in the dashboard URL
 * ({@code app.interakt.ai/template/<codename>/view}). So the CRM has to model
 * the metadata itself.
 *
 * <p>Keeping {@link #interaktName} in the database rather than in a Java enum
 * is deliberate: if the dashboard code name drifts (or Meta rejects a
 * template), ops corrects one row instead of shipping a redeploy.
 * {@link #expectedParams} is the guard that stops us burning a plan quota on a
 * render that Interakt would reject anyway.
 */
@Entity
@Table(name = "whatsapp_templates")
public class WhatsAppTemplate extends CreatedUpdated {

    /** Policy bucket a send carries. MARKETING sends go through the consent gate. */
    public enum Category { TRANSACTIONAL, MARKETING, OTP }

    /** Meta's approval state as mirrored by Interakt. Only APPROVED templates relay. */
    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }

    @Id
    @Column(length = 60)
    private String code;

    @Column(name = "interakt_name", nullable = false, length = 120)
    private String interaktName;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "expected_params", nullable = false)
    private int expectedParams;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category = Category.TRANSACTIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Column(nullable = false)
    private boolean enabled;

    protected WhatsAppTemplate() {
    }

    public WhatsAppTemplate(String code, String interaktName, String label,
                            String languageCode, int expectedParams, boolean enabled) {
        this.code = code;
        this.interaktName = interaktName;
        this.label = label;
        this.languageCode = languageCode;
        this.expectedParams = expectedParams;
        this.enabled = enabled;
    }

    public String getCode() { return code; }
    public String getInteraktName() { return interaktName; }
    public String getLabel() { return label; }
    public String getLanguageCode() { return languageCode; }
    public int getExpectedParams() { return expectedParams; }
    public Category getCategory() { return category; }
    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public boolean isEnabled() { return enabled; }
}
