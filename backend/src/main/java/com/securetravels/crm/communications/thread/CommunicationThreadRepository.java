package com.securetravels.crm.communications.thread;

import com.securetravels.crm.communications.SubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommunicationThreadRepository extends JpaRepository<CommunicationThread, UUID> {

    /** The single inbox conversation for a subject on a channel. */
    Optional<CommunicationThread> findBySubjectTypeAndSubjectIdAndChannel(
            SubjectType subjectType, UUID subjectId, CommunicationChannel channel);

    /** Inbox listing. Every filter is optional; nulls mean "any". */
    @Query("""
            select t from CommunicationThread t
             where (:assignedTo is null or t.assignedTo = :assignedTo)
               and (:status      is null or t.status      = :status)
               and (:channel     is null or t.channel     = :channel)
               and (:unreadOnly  = false  or t.unreadCount > 0)
             order by t.lastMessageAt desc
            """)
    Page<CommunicationThread> inbox(@Param("assignedTo") UUID assignedTo,
                                   @Param("status") ThreadStatus status,
                                   @Param("channel") CommunicationChannel channel,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    /** Threads with an open service window, for the window-expiry sweeper. */
    @Query("select t from CommunicationThread t where t.windowExpiresAt is not null and t.windowExpiresAt <= :now")
    Page<CommunicationThread> findWithExpiredWindow(@Param("now") Instant now, Pageable pageable);
}
