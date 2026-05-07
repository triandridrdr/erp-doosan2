package com.doosan.erp.salesprototype.service;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.ResourceNotFoundException;
import com.doosan.erp.salesprototype.dto.SalesOrderPrototypeResponse;
import com.doosan.erp.salesprototype.entity.SalesOrderPrototype;
import com.doosan.erp.salesprototype.repository.SalesOrderPrototypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class SalesOrderPrototypeService {

    private final SalesOrderPrototypeRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SalesOrderPrototypeResponse create(String analyzedFileName, Object payload) {
        try {
            SalesOrderPrototype e = new SalesOrderPrototype();
            e.setAnalyzedFileName(analyzedFileName);
            e.setSalesOrderNumber(extractSalesOrderNumber(payload));
            e.setPayloadJson(objectMapper.writeValueAsString(payload));
            SalesOrderPrototype saved = repository.save(e);
            return SalesOrderPrototypeResponse.from(saved);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize payload", ex);
        }
    }

    @Transactional
    public SalesOrderPrototypeResponse update(Long id, Object payload) {
        try {
            SalesOrderPrototype e = repository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND));
            e.setSalesOrderNumber(extractSalesOrderNumber(payload));
            e.setPayloadJson(objectMapper.writeValueAsString(payload));
            SalesOrderPrototype saved = repository.save(e);
            return SalesOrderPrototypeResponse.from(saved);
        } catch (ResourceNotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize payload", ex);
        }
    }

    @Transactional
    public void delete(Long id) {
        SalesOrderPrototype e = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND));
        repository.delete(e);
    }

    public List<SalesOrderPrototypeResponse> list() {
        return repository.findAll().stream().map(SalesOrderPrototypeResponse::from).toList();
    }

    public SalesOrderPrototypeResponse get(Long id) {
        SalesOrderPrototype e = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND));
        return SalesOrderPrototypeResponse.from(e);
    }

    private String extractSalesOrderNumber(Object payload) {
        if (payload instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) payload;
            Object ffObj = m.get("formFields");
            if (ffObj instanceof Map) {
                Map<?, ?> ff = (Map<?, ?>) ffObj;
                // Try multiple possible keys for SO number
                Object so = ff.get("SO Number");
                if (so != null && !so.toString().isBlank()) return so.toString().trim();
                so = ff.get("Order No");
                if (so != null && !so.toString().isBlank()) return so.toString().trim();
                so = ff.get("Purchase Order No");
                if (so != null && !so.toString().isBlank()) return so.toString().trim();
                so = ff.get("SO");
                if (so != null && !so.toString().isBlank()) return so.toString().trim();
            }
            Object direct = m.get("salesOrderNumber");
            if (direct != null && !direct.toString().isBlank()) return direct.toString().trim();

            // Fallback: extract from analyzedFileName (format: SONUMBER_DocumentType_...)
            Object fnObj = m.get("analyzedFileName");
            if (fnObj != null) {
                String fn = fnObj.toString().trim();
                // Extract leading digits or digits-with-dash before the first underscore
                int idx = fn.indexOf('_');
                if (idx > 0) {
                    String prefix = fn.substring(0, idx);
                    if (prefix.matches("\\d+(-\\d+)?")) {
                        // Use just the base number (before dash) as SO number
                        int dashIdx = prefix.indexOf('-');
                        return dashIdx > 0 ? prefix.substring(0, dashIdx) : prefix;
                    }
                }
            }
        }
        return null;
    }

    @Transactional
    public SalesOrderPrototypeResponse createOrMerge(String documentType, String analyzedFileName, Object payload) {
        try {
            String soNumber = extractSalesOrderNumber(payload);
            if (soNumber == null || soNumber.isBlank()) {
                throw new IllegalArgumentException("SO Number not found in payload");
            }

            // Find existing by SO Number
            Optional<SalesOrderPrototype> existingOpt = repository.findBySalesOrderNumber(soNumber);

            SalesOrderPrototype entity;
            boolean isNew = false;
            if (existingOpt.isPresent()) {
                // MERGE to existing record
                entity = existingOpt.get();
                log.info("[MERGE] Found existing record id={} for SO Number={}", entity.getId(), soNumber);
            } else {
                // CREATE new
                entity = new SalesOrderPrototype();
                entity.setSalesOrderNumber(soNumber);
                isNew = true;
                log.info("[MERGE] Creating new record for SO Number={}", soNumber);
            }

            // Update based on document type
            String jsonData = objectMapper.writeValueAsString(payload);
            switch (documentType) {
                case "purchase-order":
                    entity.setPurchaseOrderUploaded(true);
                    entity.setPurchaseOrderJson(jsonData);
                    entity.setAnalyzedFileName(analyzedFileName);
                    break;
                case "supplementary":
                    entity.setSupplementaryUploaded(true);
                    entity.setSupplementaryJson(jsonData);
                    break;
                case "size-per-colour-breakdown":
                    entity.setSizePerColourUploaded(true);
                    entity.setSizePerColourJson(jsonData);
                    break;
                case "total-country-breakdown":
                    entity.setTotalCountryUploaded(true);
                    entity.setTotalCountryJson(jsonData);
                    break;
                default:
                    // fallback: save to main payloadJson
                    entity.setPayloadJson(jsonData);
                    entity.setAnalyzedFileName(analyzedFileName);
                    log.warn("[MERGE] Unknown documentType={}, saving to main payloadJson", documentType);
            }

            // Build merged payload for backward compatibility
            entity.setPayloadJson(buildMergedPayload(entity));

            SalesOrderPrototype saved = repository.save(entity);
            log.info("[MERGE] {} record id={} for SO Number={}, documentType={}",
                    isNew ? "Created" : "Updated", saved.getId(), soNumber, documentType);
            return SalesOrderPrototypeResponse.from(saved);
        } catch (Exception ex) {
            log.error("[MERGE] Failed to save/merge: {}", ex.getMessage(), ex);
            throw new IllegalArgumentException("Failed to save/merge: " + ex.getMessage(), ex);
        }
    }

    private String buildMergedPayload(SalesOrderPrototype entity) throws Exception {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("salesOrderNumber", entity.getSalesOrderNumber());
        merged.put("purchaseOrderUploaded", Boolean.TRUE.equals(entity.getPurchaseOrderUploaded()));
        merged.put("supplementaryUploaded", Boolean.TRUE.equals(entity.getSupplementaryUploaded()));
        merged.put("sizePerColourUploaded", Boolean.TRUE.equals(entity.getSizePerColourUploaded()));
        merged.put("totalCountryUploaded", Boolean.TRUE.equals(entity.getTotalCountryUploaded()));

        if (entity.getPurchaseOrderJson() != null) {
            merged.put("purchaseOrder", objectMapper.readValue(entity.getPurchaseOrderJson(), Map.class));
        }
        if (entity.getSupplementaryJson() != null) {
            merged.put("supplementary", objectMapper.readValue(entity.getSupplementaryJson(), Map.class));
        }
        if (entity.getSizePerColourJson() != null) {
            merged.put("sizePerColourBreakdown", objectMapper.readValue(entity.getSizePerColourJson(), Map.class));
        }
        if (entity.getTotalCountryJson() != null) {
            merged.put("totalCountryBreakdown", objectMapper.readValue(entity.getTotalCountryJson(), Map.class));
        }

        return objectMapper.writeValueAsString(merged);
    }
}
