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
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "debug", required = false) Boolean debug,
            @Parameter(description = "Use hOCR mode for better handling of fragmented text (split words across lines)")
            @RequestParam(value = "useHocr", required = false, defaultValue = "true") Boolean useHocr,
            @Parameter(description = "Log hOCR and non-hOCR outputs side by side for debugging")
            @RequestParam(value = "compareModes", required = false, defaultValue = "false") Boolean compareModes
    ) {
        OcrNewDocumentAnalysisResponse response = ocrNewService.analyzeDocument(
                file,
                debug,
                Boolean.TRUE.equals(useHocr),
                Boolean.TRUE.equals(compareModes)
        );
        return ResponseEntity.ok(ApiResponse.success(response, "OCR-NEW analysis completed"));
    }

    @Operation(
            summary = "Document analysis with hOCR (recommended for complex text)",
            description = "Uses hOCR extraction for better handling of split words, line continuations, and multi-page text. " +
                    "Recommended for textile/garment documents with complex Description and Composition fields."
    )
    @PostMapping(value = "/analyze-hocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OcrNewDocumentAnalysisResponse>> analyzeWithHocr(
            @Parameter(
                    description = "PDF/PNG/JPG file",
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
            )
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "debug", required = false) Boolean debug
    ) {
        OcrNewDocumentAnalysisResponse response = ocrNewService.analyzeDocument(file, debug, true);
        return ResponseEntity.ok(ApiResponse.success(response, "OCR-NEW (hOCR mode) analysis completed"));
    }
}
