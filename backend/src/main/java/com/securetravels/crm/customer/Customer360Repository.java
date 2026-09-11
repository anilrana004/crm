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
            where (:search is null or lower(c.fullName) like lower(concat('%', :search, '%'))
               or lower(coalesce(c.mobileNumber, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(c.email, '')) like lower(concat('%', :search, '%')))
            order by c.updatedAt desc""")
    List<Customer360> search(@Param("search") String search);
}
