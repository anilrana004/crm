package com.securetravels.crm.communications;

import com.securetravels.crm.booking.BookingService;
import com.securetravels.crm.communications.dto.TimelineResponse;
import com.securetravels.crm.customer.Customer360Service;
import com.securetravels.crm.lead.LeadService;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the Lead/Customer/Booking timeline (Module 4).
 *
 * <p>Authorisation is delegated to the owning module's {@code get(...)} call
 * rather than reimplemented. The ownership rules already live in
 * {@code LeadService.assertCanAccess} (owner-or-elevated), and duplicating that
 * logic here is exactly how a timeline endpoint ends up leaking one salesperson's
 * leads to another. Calling the owner keeps one rule and one place to fix it.
 *
 * <p>It also respects the module boundary: this service touches no other
 * module's entity, only its service interface.
 */
@Service
public class TimelineService {

    private final TimelineEventRepository timeline;
    private final LeadService leads;
    private final BookingService bookings;
    private final Customer360Service customers;

    public TimelineService(TimelineEventRepository timeline, LeadService leads,
                           BookingService bookings, Customer360Service customers) {
        this.timeline = timeline;
        this.leads = leads;
        this.bookings = bookings;
        this.customers = customers;
    }

    /** Newest first. Throws NotFound/Forbidden exactly as the owning module would. */
    @Transactional(readOnly = true)
    public List<TimelineResponse> list(SubjectType subjectType, UUID subjectId, UserPrincipal caller) {
        assertCanRead(subjectType, subjectId, caller);
        return timeline.findBySubjectTypeAndSubjectIdOrderByCreatedAtDescSeqDesc(subjectType, subjectId)
                .stream()
                .map(TimelineResponse::from)
                .toList();
    }

    /**
     * Reuse this for anything that touches a subject's communications — a
     * manual send is exactly as much a disclosure of that customer's data as
     * reading their timeline is.
     */
    @Transactional(readOnly = true)
    public void assertCanRead(SubjectType subjectType, UUID subjectId, UserPrincipal caller) {
        authorize(subjectType, subjectId, caller);
    }

    private void authorize(SubjectType subjectType, UUID subjectId, UserPrincipal caller) {
        switch (subjectType) {
            case LEAD -> leads.get(subjectId, caller);
            case BOOKING -> bookings.get(subjectId, caller);
            case CUSTOMER -> customers.get(subjectId, caller);
        }
    }
}
