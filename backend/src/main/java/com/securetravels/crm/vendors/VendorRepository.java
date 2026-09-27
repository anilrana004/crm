package com.securetravels.crm.vendors;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    List<Vendor> findAllByActiveTrueOrderByNameAsc();

    List<Vendor> findAllByOrderByNameAsc();

    List<Vendor> findAllByActiveTrueAndCategoryOrderByNameAsc(Vendor.Category category);

    List<Vendor> findAllByCategoryOrderByNameAsc(Vendor.Category category);

    boolean existsByIdAndCategory(UUID id, Vendor.Category category);
}