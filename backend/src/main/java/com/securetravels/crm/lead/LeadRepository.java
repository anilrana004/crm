package com.securetravels.crm.lead;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    /** Duplicate detection key — first non-LOST lead sharing the phone. */
    @Query("""
            select l from Lead l
            where l.mobileDigits = :digits
              and l.status <> com.securetravels.crm.lead.Lead.Status.LOST
            order by l.createdAt desc
            limit 1
            """)
    Optional<Lead> findFirstActiveDuplicate(@Param("digits") String digits);

    /**
     * Filtered listing. All parameters are optional; the SQL is assembled to
     * use the (owner_id, status, created_at) index when scoping by owner.
     */
    @Query(value = """
            select * from leads l
            where (CAST(:ownerId AS uuid) is null or l.owner_id = :ownerId)
              and (CAST(:status AS varchar) is null or l.status = :status)
              and (CAST(:source AS varchar) is null or l.source = :source)
              and (CAST(:heat AS varchar) is null or l.heat = :heat)
              and (CAST(:tripId AS uuid) is null or l.trip_id = :tripId)
              and (CAST(:from AS timestamptz) is null or l.created_at >= :from)
              and (CAST(:to AS timestamptz) is null or l.created_at < :to)
              and (CAST(:travelFrom AS date) is null or l.travel_date >= :travelFrom)
              and (CAST(:travelTo AS date) is null or l.travel_date <= :travelTo)
              and (CAST(:search AS varchar) is null or lower(l.customer_name) like lower('%' || :search || '%')
                   or l.mobile_digits like '%' || :search || '%')
            order by l.created_at desc
            """,
            countQuery = """
            select count(*) from leads l
            where (CAST(:ownerId AS uuid) is null or l.owner_id = :ownerId)
              and (CAST(:status AS varchar) is null or l.status = :status)
              and (CAST(:source AS varchar) is null or l.source = :source)
              and (CAST(:heat AS varchar) is null or l.heat = :heat)
              and (CAST(:tripId AS uuid) is null or l.trip_id = :tripId)
              and (CAST(:from AS timestamptz) is null or l.created_at >= :from)
              and (CAST(:to AS timestamptz) is null or l.created_at < :to)
              and (CAST(:travelFrom AS date) is null or l.travel_date >= :travelFrom)
              and (CAST(:travelTo AS date) is null or l.travel_date <= :travelTo)
              and (CAST(:search AS varchar) is null or lower(l.customer_name) like lower('%' || :search || '%')
                   or l.mobile_digits like '%' || :search || '%')
            """,
            nativeQuery = true)
    Page<Lead> search(@Param("ownerId") UUID ownerId,
                      @Param("status") String status,
                      @Param("source") String source,
                      @Param("heat") String heat,
                      @Param("tripId") UUID tripId,
                      @Param("from") java.time.Instant from,
                      @Param("to") java.time.Instant to,
                      @Param("travelFrom") LocalDate travelFrom,
                      @Param("travelTo") LocalDate travelTo,
                      @Param("search") String search,
                      Pageable pageable);
}