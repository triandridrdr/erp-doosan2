package com.doosan.erp.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassifiedDocumentDto {

    private Map<String, String> salesOrderHeader;

    private List<Map<String, String>> salesOrderDetails;

    private List<Map<String, String>> bomItems;

    private Map<String, String> unmappedFields;
}
