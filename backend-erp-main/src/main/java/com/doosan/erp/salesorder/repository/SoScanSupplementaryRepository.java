package com.doosan.erp.salesorder.repository;

import com.doosan.erp.salesorder.entity.SoScanSupplementary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoScanSupplementaryRepository extends JpaRepository<SoScanSupplementary, Long> {

    List<SoScanSupplementary> findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);

    Optional<SoScanSupplementary> findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);
}
