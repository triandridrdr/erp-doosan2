package com.doosan.erp.salesprototype.service;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.ResourceNotFoundException;
import com.doosan.erp.salesprototype.dto.SalesOrderPrototypeResponse;
import com.doosan.erp.salesprototype.entity.SalesOrderPrototype;
import com.doosan.erp.salesprototype.repository.SalesOrderPrototypeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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
                Object so = ff.get("SO Number");
                if (so != null) return so.toString();
            }
            Object direct = m.get("salesOrderNumber");
            if (direct != null) return direct.toString();
        }
        return null;
    }
}
