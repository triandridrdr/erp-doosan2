package com.doosan.erp.salesorder.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SaveDraftResponse {
    private Long soHeaderId;
    private String soNumber;
    private Long scanId;
    private String documentType;
    private Integer revision;
    private String message;
}
