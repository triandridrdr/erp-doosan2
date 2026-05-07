package com.doosan.erp.salesorder.repository;

import com.doosan.erp.salesorder.entity.SoHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SoHeaderRepository extends JpaRepository<SoHeader, Long> {

    Optional<SoHeader> findBySoNumberAndDeletedFalse(String soNumber);

    List<SoHeader> findByDeletedFalseOrderByCreatedAtDesc();

    List<SoHeader> findByWorkflowStatusAndDeletedFalse(com.doosan.erp.salesorder.entity.SoWorkflowStatus status);
}
