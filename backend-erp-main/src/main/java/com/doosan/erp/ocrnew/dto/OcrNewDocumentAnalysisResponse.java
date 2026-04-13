package com.doosan.erp.ocrnew.dto;

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
public class OcrNewDocumentAnalysisResponse {

    private String extractedText;

    private List<OcrNewLineDto> lines;

    private List<OcrNewTableDto> tables;

    private List<OcrNewKeyValuePairDto> keyValuePairs;

    private Map<String, String> formFields;

    private List<Map<String, String>> salesOrderDetailSizeBreakdown;

    private Float averageConfidence;

    private Integer pageCount;
}
