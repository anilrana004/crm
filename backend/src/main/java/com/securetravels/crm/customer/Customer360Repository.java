package com.securetravels.crm.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Customer360Repository extends JpaRepository<Customer360, UUID> {
    Optional<Customer360> findByMobileDigits(String mobileDigits);

    @Query("""
            select c from Customer360 c
            where (:pattern is null or lower(c.fullName) like :pattern
               or lower(coalesce(c.mobileNumber, '')) like :pattern
               or lower(coalesce(c.email, '')) like :pattern)
            order by c.updatedAt desc""")
    List<Customer360> searchPattern(@Param("pattern") String pattern);

    default List<Customer360> search(String search) {
        // The pattern (and its lower-casing) is prepared in Java, so the
        // query never binds a parameter inside lower()/concat(); PostgreSQL
        // otherwise types a null untyped parameter as bytea and fails with
        // "function lower(bytea) does not exist" (see LeadRepository for the
        // sibling native-SQL convention).
        if (search == null) {
            return searchPattern(null);
        }
        return searchPattern("%" + search.toLowerCase() + "%");
    }
}
