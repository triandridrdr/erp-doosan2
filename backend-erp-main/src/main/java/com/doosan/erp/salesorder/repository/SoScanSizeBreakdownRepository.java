package com.doosan.erp.salesorder.repository;

import com.doosan.erp.salesorder.entity.SoScanSizeBreakdown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoScanSizeBreakdownRepository extends JpaRepository<SoScanSizeBreakdown, Long> {

    List<SoScanSizeBreakdown> findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);

    Optional<SoScanSizeBreakdown> findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);
}
