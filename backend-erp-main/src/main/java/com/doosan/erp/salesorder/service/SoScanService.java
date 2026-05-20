package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.SoHeader;
import com.doosan.erp.salesorder.repository.SoHeaderRepository;
import com.doosan.erp.salesorder.util.SoScanHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SoScanService {

    private final SoHeaderRepository headerRepo;
    private final SoScanHelper helper;
    private final SoScanSupplementaryService supplementaryService;
    private final SoScanPurchaseOrderService purchaseOrderService;
    private final SoScanSizeBreakdownService sizeBreakdownService;
    private final SoScanCountryBreakdownService countryBreakdownService;

    @Transactional
    public SaveDraftResponse saveDraft(String documentType, Map<String, Object> payload) {
        String soNumber = helper.extractSoNumber(payload);
        if (soNumber == null || soNumber.isBlank()) {
            throw new IllegalArgumentException("SO Number is required in formFields");
        }

        String fileName = helper.str(payload.get("analyzedFileName"));

        SoHeader header = headerRepo.findBySoNumberAndDeletedFalse(soNumber)
                .orElseGet(() -> {
                    SoHeader h = new SoHeader();
                    h.setSoNumber(soNumber);
                    h.setWorkflowStatus(com.doosan.erp.salesorder.entity.SoWorkflowStatus.DRAFT_OCR);
                    return h;
                });

        helper.mergeHeaderFields(header, payload);
        helper.logPotentialHeaderLengthIssues(header);
        header = headerRepo.save(header);
        helper.ensureMasterSizes(payload);

        return switch (documentType) {
            case "all" -> saveAll(header, fileName, payload);
            case "supplementary" -> supplementaryService.saveSupplementary(header, fileName, payload);
            case "purchase-order" -> purchaseOrderService.savePurchaseOrder(header, fileName, payload);
            case "size-per-colour-breakdown" -> sizeBreakdownService.saveSizeBreakdown(header, fileName, payload);
            case "total-country-breakdown" -> countryBreakdownService.saveCountryBreakdown(header, fileName, payload);
            default -> throw new IllegalArgumentException("Unknown documentType: " + documentType);
        };
    }

    private SaveDraftResponse saveAll(SoHeader header, String fileName, Map<String, Object> payload) {
        supplementaryService.saveSupplementary(header, fileName, payload);
        purchaseOrderService.savePurchaseOrder(header, fileName, payload);
        sizeBreakdownService.saveSizeBreakdown(header, fileName, payload);
        countryBreakdownService.saveCountryBreakdown(header, fileName, payload);

        log.info("[ALL_SAVE] SO={} all document types saved successfully", header.getSoNumber());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(null)
                .documentType("all")
                .revision(null)
                .message("All document types saved successfully")
                .build();
    }
}
