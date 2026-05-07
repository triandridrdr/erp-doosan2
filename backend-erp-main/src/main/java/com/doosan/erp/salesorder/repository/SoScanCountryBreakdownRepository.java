package com.doosan.erp.salesorder.repository;

import com.doosan.erp.salesorder.entity.SoScanCountryBreakdown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoScanCountryBreakdownRepository extends JpaRepository<SoScanCountryBreakdown, Long> {

    List<SoScanCountryBreakdown> findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);

    Optional<SoScanCountryBreakdown> findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);
}
