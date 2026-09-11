package com.securetravels.crm.lead;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class LeadScoringServiceTest {

    private LeadScoringService scoring;
    private Lead lead;

    @BeforeEach
    void setUp() {
        scoring = new LeadScoringService();
        lead = new Lead();
        lead.setTravelDate(LocalDate.now().plusDays(120));
        lead.setBudget(new BigDecimal("10000"));
        lead.setNumPersons(1);
        lead.setSource(Lead.Source.WHATSAPP);
    }

    @Test
    void defaultsToCold() {
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.COLD);
    }

    @Test
    void hotWhenDepartureWithinThirtyDays() {
        lead.setTravelDate(LocalDate.now().plusDays(15));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.HOT);
    }

    @Test
    void hotWhenBudgetAtOrAboveFiftyThousand() {
        lead.setBudget(new BigDecimal("50000"));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.HOT);
    }

    @Test
    void hotWhenWebLeadWithLargeParty() {
        lead.setSource(Lead.Source.WEBSITE);
        lead.setNumPersons(4);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.HOT);
    }

    @Test
    void warmWhenDepartureWithinNinetyDays() {
        lead.setTravelDate(LocalDate.now().plusDays(60));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void coldWhenNearlyIdenticalButLowBudgetSmallPartyFarFuture() {
        lead.setTravelDate(LocalDate.now().plusDays(200));
        lead.setBudget(new BigDecimal("15000"));
        lead.setNumPersons(1);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.COLD);
    }

    @Test
    void hotAtExactlyThirtyDaysOut() {
        lead.setTravelDate(LocalDate.now().plusDays(30));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.HOT);
    }

    @Test
    void warmAtThirtyOneDaysOut() {
        lead.setTravelDate(LocalDate.now().plusDays(31));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void warmAtExactlyNinetyDaysOut() {
        lead.setTravelDate(LocalDate.now().plusDays(90));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void coldAtNinetyOneDaysOut() {
        lead.setTravelDate(LocalDate.now().plusDays(91));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.COLD);
    }

    @Test
    void budgetJustBelowHotThresholdIsWarm() {
        lead.setBudget(new BigDecimal("49999.99"));
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void googleAdsLargerPartyIsHot() {
        lead.setSource(Lead.Source.GOOGLE_ADS);
        lead.setNumPersons(4);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.HOT);
    }

    @Test
    void threePersonWebsiteIsWarmNotHot() {
        lead.setSource(Lead.Source.WEBSITE);
        lead.setNumPersons(3);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void referralIsWarmRegardlessOfOtherSignals() {
        lead.setSource(Lead.Source.REFERRAL);
        lead.setBudget(new BigDecimal("5000"));
        lead.setNumPersons(1);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.WARM);
    }

    @Test
    void lostLeadStaysColdEvenWhenFieldsLookHot() {
        lead.setStatus(Lead.Status.LOST);
        lead.setTravelDate(LocalDate.now().plusDays(15));
        lead.setBudget(new BigDecimal("50000"));
        lead.setSource(Lead.Source.WEBSITE);
        lead.setNumPersons(4);
        assertThat(scoring.score(lead)).isEqualTo(Lead.Heat.COLD);
    }
}