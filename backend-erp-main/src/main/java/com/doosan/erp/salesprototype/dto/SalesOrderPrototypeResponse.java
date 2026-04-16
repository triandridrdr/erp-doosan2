package com.doosan.erp.salesprototype.dto;

import com.doosan.erp.salesprototype.entity.SalesOrderPrototype;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SalesOrderPrototypeResponse {
    private Long id;
    private String salesOrderNumber;
    private String analyzedFileName;
    private String payloadJson;
    private LocalDateTime createdAt;
    private String createdBy;

    public static SalesOrderPrototypeResponse from(SalesOrderPrototype e) {
        return SalesOrderPrototypeResponse.builder()
                .id(e.getId())
                .salesOrderNumber(e.getSalesOrderNumber())
                .analyzedFileName(e.getAnalyzedFileName())
                .payloadJson(e.getPayloadJson())
                .createdAt(e.getCreatedAt())
                .createdBy(e.getCreatedBy())
                .build();
    }
}
