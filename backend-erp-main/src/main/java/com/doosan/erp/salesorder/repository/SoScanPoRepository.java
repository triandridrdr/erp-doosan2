package com.doosan.erp.salesorder.repository;

import com.doosan.erp.salesorder.entity.SoScanPo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoScanPoRepository extends JpaRepository<SoScanPo, Long> {

    List<SoScanPo> findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);

    Optional<SoScanPo> findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(Long soHeaderId);
}
