package com.securetravels.crm.lead;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Rule-based Hot/Warm/Cold scoring, evaluated at lead creation and whenever
 * key fields change. Rules are deliberately simple and documented:
 *
 *  HOT  — departure target within 30 days,
 *         OR budget >= 50,000,
 *         OR (source WEBSITE/GOOGLE_ADS AND num_persons >= 4)
 *  WARM — departure target within 90 days,
 *         OR budget >= 20,000,
 *         OR num_persons >= 2,
 *         OR source REFERRAL
 *  COLD — everything else (and LOST leads)
 */
@Service
public class LeadScoringService {

    private static final BigDecimal BUDGET_HOT = new BigDecimal("50000");
    private static final BigDecimal BUDGET_WARM = new BigDecimal("20000");

    public Lead.Heat score(Lead lead) {
        // LOST is absorbing; a lost lead never re-heats, even if its enquiry
        // fields still look hot (a lost lead can still be edited).
        if (lead.getStatus() == Lead.Status.LOST) {
            return Lead.Heat.COLD;
        }
        LocalDate today = LocalDate.now();

        if (isHot(lead, today)) return Lead.Heat.HOT;
        if (isWarm(lead, today)) return Lead.Heat.WARM;
        return Lead.Heat.COLD;
    }

    private boolean isHot(Lead l, LocalDate today) {
        if (l.getTravelDate() != null && !l.getTravelDate().isAfter(today.plusDays(30))) return true;
        if (l.getBudget() != null && l.getBudget().compareTo(BUDGET_HOT) >= 0) return true;
        if (l.getNumPersons() >= 4
                && (l.getSource() == Lead.Source.WEBSITE || l.getSource() == Lead.Source.GOOGLE_ADS)) {
            return true;
        }
        return false;
    }

    private boolean isWarm(Lead l, LocalDate today) {
        if (l.getTravelDate() != null && !l.getTravelDate().isAfter(today.plusDays(90))) return true;
        if (l.getBudget() != null && l.getBudget().compareTo(BUDGET_WARM) >= 0) return true;
        if (l.getNumPersons() >= 2) return true;
        return l.getSource() == Lead.Source.REFERRAL;
    }
}