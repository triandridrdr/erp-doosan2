package com.doosan.erp.master.repository;

import com.doosan.erp.master.entity.MasterSize;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MasterSizeRepository extends JpaRepository<MasterSize, Long> {

    Optional<MasterSize> findByNormalizedLabel(String normalizedLabel);

    @Query("""
            select s from MasterSize s
            where s.deleted = false and s.active = true
            order by s.sortOrder asc, s.id asc
            """)
    List<MasterSize> findAllActive();

    @Query("""
            select s from MasterSize s
            where s.deleted = false
            order by s.sortOrder asc, s.id asc
            """)
    List<MasterSize> findAllIncludingInactive();
}
