package com.doosan.erp.ocrnew.service;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.BusinessException;
import com.doosan.erp.ocrnew.dto.OcrNewBoundingBoxDto;
import com.doosan.erp.ocrnew.dto.OcrNewDocumentAnalysisResponse;
import com.doosan.erp.ocrnew.dto.OcrNewKeyValuePairDto;
import com.doosan.erp.ocrnew.dto.OcrNewLineDto;
import com.doosan.erp.ocrnew.dto.OcrNewTableDto;
import com.doosan.erp.ocrnew.engine.PdfToImageRenderer;
import com.doosan.erp.ocrnew.engine.TesseractOcrEngine;
import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.parser.KeyValueParser;
import com.doosan.erp.ocrnew.parser.TableParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class OcrNewService {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "application/pdf"
    );

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final PdfToImageRenderer pdfToImageRenderer = new PdfToImageRenderer();
    private final KeyValueParser keyValueParser = new KeyValueParser();
    private final TableParser tableParser = new TableParser();

    private final float renderDpi;
    private final TesseractOcrEngine ocrEngine;
    private final boolean debugLogging;

    public OcrNewService(
            @Value("${ocrnew.render.dpi:300}") float renderDpi,
            @Value("${ocrnew.tesseract.datapath:}") String tessDataPath,
            @Value("${ocrnew.tesseract.language:eng}") String language,
            @Value("${ocrnew.debug.logging:false}") boolean debugLogging
    ) {
        this.renderDpi = renderDpi;
        this.ocrEngine = new TesseractOcrEngine(tessDataPath, language);
        this.debugLogging = debugLogging;
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file) {
        validateFile(file);

        try {
            byte[] fileBytes = file.getBytes();

            if (debugLogging) {
                log.info("[OCR-NEW][DEBUG] analyzeDocument start: fileName={}, contentType={}, sizeBytes={}, renderDpi={}",
                        file.getOriginalFilename(), file.getContentType(), fileBytes.length, renderDpi);
            }

            List<BufferedImage> pageImages;
            if (isPdf(file)) {
                pageImages = pdfToImageRenderer.renderPdfToImages(fileBytes, renderDpi);
            } else {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(fileBytes));
                if (img == null) {
                    throw new BusinessException(ErrorCode.OCR_INVALID_FILE, "이미지 파일을 읽을 수 없습니다");
                }
                pageImages = List.of(img);
            }

            if (debugLogging) {
                log.info("[OCR-NEW][DEBUG] rendered pages: pageCount={}", pageImages.size());
                for (int i = 0; i < Math.min(pageImages.size(), 5); i++) {
                    BufferedImage img = pageImages.get(i);
                    log.info("[OCR-NEW][DEBUG] pageImage[{}]: width={}, height={}", i, img.getWidth(), img.getHeight());
                }
                if (pageImages.size() > 5) {
                    log.info("[OCR-NEW][DEBUG] pageImage: {} more pages not logged", pageImages.size() - 5);
                }
            }

            List<OcrNewLine> allLines = new ArrayList<>();
            for (int i = 0; i < pageImages.size(); i++) {
                allLines.addAll(ocrEngine.extractLinesFromImage(pageImages.get(i), i));
            }

            allLines.sort(Comparator
                    .comparingInt(OcrNewLine::getPage)
                    .thenComparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            if (debugLogging) {
                log.info("[OCR-NEW][DEBUG] ocr lines: count={}", allLines.size());
                for (int i = 0; i < Math.min(allLines.size(), 80); i++) {
                    OcrNewLine l = allLines.get(i);
                    log.info("[OCR-NEW][DEBUG] line[{}]: page={}, bbox=[{},{}-{},{}], conf={}, text={}",
                            i,
                            l.getPage(),
                            l.getLeft(), l.getTop(), l.getRight(), l.getBottom(),
                            round1(l.getConfidence()),
                            truncate(oneLine(l.getText()), 240));
                }
                if (allLines.size() > 80) {
                    log.info("[OCR-NEW][DEBUG] lines: {} more lines not logged", allLines.size() - 80);
                }
            }

            List<OcrNewKeyValuePairDto> pairs = keyValueParser.parseKeyValuePairs(allLines);
            Map<String, String> formFields = keyValueParser.toFieldMap(pairs);
            List<OcrNewTableDto> tables = tableParser.parseTables(allLines);

            String extractedText = allLines.stream()
                    .map(OcrNewLine::getText)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

            if (debugLogging) {
                log.info("[OCR-NEW][DEBUG] extractedText preview: {}", truncate(extractedText, 4000));
                log.info("[OCR-NEW][DEBUG] keyValuePairs: count={}", pairs.size());
                for (int i = 0; i < Math.min(pairs.size(), 80); i++) {
                    OcrNewKeyValuePairDto p = pairs.get(i);
                    log.info("[OCR-NEW][DEBUG] kv[{}]: page={}, conf={}, key={}, value={}",
                            i,
                            p.getPage(),
                            p.getConfidence() == null ? null : round1(p.getConfidence()),
                            truncate(oneLine(p.getKey()), 120),
                            truncate(oneLine(p.getValue()), 240));
                }
                if (pairs.size() > 80) {
                    log.info("[OCR-NEW][DEBUG] keyValuePairs: {} more not logged", pairs.size() - 80);
                }

                log.info("[OCR-NEW][DEBUG] formFields: count={}", formFields.size());
                formFields.entrySet().stream()
                        .filter(en -> en.getKey() != null && en.getValue() != null)
                        .sorted(Map.Entry.comparingByKey())
                        .limit(80)
                        .forEach(en -> log.info("[OCR-NEW][DEBUG] field: {} = {}", truncate(oneLine(en.getKey()), 120), truncate(oneLine(en.getValue()), 240)));
                if (formFields.size() > 80) {
                    log.info("[OCR-NEW][DEBUG] formFields: {} more not logged", formFields.size() - 80);
                }

                log.info("[OCR-NEW][DEBUG] tables: count={}", tables.size());
                for (int i = 0; i < Math.min(tables.size(), 20); i++) {
                    OcrNewTableDto t = tables.get(i);
                    log.info("[OCR-NEW][DEBUG] table[{}]: page={}, index={}, rows={}, cols={}", i, t.getPage(), t.getIndex(), t.getRowCount(), t.getColumnCount());
                    if (t.getRows() != null && !t.getRows().isEmpty()) {
                        for (int r = 0; r < Math.min(t.getRows().size(), 5); r++) {
                            List<String> row = t.getRows().get(r);
                            String rowText = row == null ? "" : row.stream().filter(Objects::nonNull).map(this::oneLine).map(s -> truncate(s, 80)).reduce("", (a, b) -> a.isEmpty() ? b : a + " | " + b);
                            log.info("[OCR-NEW][DEBUG] table[{}] row[{}]: {}", i, r, truncate(rowText, 600));
                        }
                    }
                }
                if (tables.size() > 20) {
                    log.info("[OCR-NEW][DEBUG] tables: {} more not logged", tables.size() - 20);
                }
            }

            float avgConfidence = 0f;
            if (!allLines.isEmpty()) {
                float sum = 0f;
                for (OcrNewLine l : allLines) sum += l.getConfidence();
                avgConfidence = sum / allLines.size();
            }

            List<OcrNewLineDto> lineDtos = allLines.stream().map(l -> OcrNewLineDto.builder()
                    .page(l.getPage())
                    .text(l.getText())
                    .boundingBox(OcrNewBoundingBoxDto.builder()
                            .left(l.getLeft())
                            .top(l.getTop())
                            .width(Math.max(0, l.getRight() - l.getLeft()))
                            .height(Math.max(0, l.getBottom() - l.getTop()))
                            .build())
                    .confidence(l.getConfidence())
                    .build()).toList();

            return OcrNewDocumentAnalysisResponse.builder()
                    .extractedText(extractedText)
                    .lines(lineDtos)
                    .tables(tables)
                    .keyValuePairs(pairs)
                    .formFields(formFields)
                    .averageConfidence(avgConfidence)
                    .pageCount(pageImages.size())
                    .build();

        } catch (IOException e) {
            log.error("OCR-NEW file read failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }
    }

    private static String truncate(String s, int maxLen) {
        String t = s == null ? "" : s;
        if (t.length() <= maxLen) return t;
        return t.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static Float round1(Float v) {
        if (v == null) return null;
        return Math.round(v * 10f) / 10f;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.OCR_FILE_EMPTY);
        }

        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.OCR_INVALID_FILE,
                    "지원하는 파일 형식: PNG, JPG, JPEG, PDF");
        }
    }

    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && PDF_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }
}
