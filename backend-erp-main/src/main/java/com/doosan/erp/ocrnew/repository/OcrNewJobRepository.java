package com.doosan.erp.ocrnew.repository;

import com.doosan.erp.ocrnew.entity.OcrNewJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OcrNewJobRepository extends JpaRepository<OcrNewJob, Long> {
    Optional<OcrNewJob> findByJobKey(String jobKey);
}
