package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SoHeaderResponse;
import com.doosan.erp.salesorder.entity.SoHeader;
import com.doosan.erp.salesorder.entity.SoWorkflowStatus;
import com.doosan.erp.salesorder.repository.SoHeaderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SoHeaderService {

    private final SoHeaderRepository headerRepo;

    @Transactional(readOnly = true)
    public List<SoHeaderResponse> listAll() {
        return headerRepo.findByDeletedFalseOrderByCreatedAtDesc()
                .stream()
                .map(SoHeaderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SoHeaderResponse getById(Long id) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        return SoHeaderResponse.from(header);
    }

    @Transactional(readOnly = true)
    public SoHeaderResponse getBySoNumber(String soNumber) {
        SoHeader header = headerRepo.findBySoNumberAndDeletedFalse(soNumber)
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + soNumber));
        return SoHeaderResponse.from(header);
    }

    @Transactional
    public SoHeaderResponse updateWorkflowStatus(Long id, SoWorkflowStatus newStatus) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        header.setWorkflowStatus(newStatus);
        header = headerRepo.save(header);
        log.info("[WORKFLOW] SO={} status changed to {}", header.getSoNumber(), newStatus);
        return SoHeaderResponse.from(header);
    }

    @Transactional
    public void softDelete(Long id) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        header.softDelete();
        headerRepo.save(header);
        log.info("[DELETE] SO={} soft deleted", header.getSoNumber());
    }
}
