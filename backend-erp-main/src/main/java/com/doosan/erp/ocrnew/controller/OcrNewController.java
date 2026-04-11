package com.doosan.erp.ocrnew.controller;

import com.doosan.erp.common.dto.ApiResponse;
import com.doosan.erp.ocrnew.dto.OcrNewDocumentAnalysisResponse;
import com.doosan.erp.ocrnew.service.OcrNewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "OCR-NEW", description = "Offline OCR (no Textract) document analysis API")
@RestController
@RequestMapping("/api/v1/ocr-new")
@RequiredArgsConstructor
public class OcrNewController {

    private final OcrNewService ocrNewService;

    @Operation(
            summary = "Document analysis (PDF/Image) - OCR-NEW",
            description = "Uploads a PDF/image, renders PDF pages to PNG (300DPI), runs OCR, detects key-value pairs and tables, and returns structured JSON."
    )
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OcrNewDocumentAnalysisResponse>> analyze(
            @Parameter(
                    description = "PDF/PNG/JPG file",
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
            )
            @RequestParam("file") MultipartFile file
    ) {
        OcrNewDocumentAnalysisResponse response = ocrNewService.analyzeDocument(file);
        return ResponseEntity.ok(ApiResponse.success(response, "OCR-NEW analysis completed"));
    }
}
