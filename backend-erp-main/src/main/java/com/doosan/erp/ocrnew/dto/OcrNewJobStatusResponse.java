package com.doosan.erp.ocrnew.dto;

import com.doosan.erp.ocrnew.entity.OcrNewJob;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OcrNewJobStatusResponse {
    private String jobId;
    private OcrNewJob.Status status;
    private Integer progressPercent;
    private String errorMessage;
    private OcrNewDocumentAnalysisResponse result;
}
