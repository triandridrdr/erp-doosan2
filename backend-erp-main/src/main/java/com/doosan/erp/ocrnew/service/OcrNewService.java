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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern SIZE_VALUE_LINE = Pattern.compile("^\\s*([A-Za-z]{1,2})\\s*(?:\\(|\\b).*?\\b(\\d[\\d\\s]{0,12})\\s*$");
    private static final Pattern QUANTITY_LINE = Pattern.compile("^\\s*(?:quantity|qty)\\s*[:#]?\\s*(\\d[\\d\\s]{0,15})\\s*$", Pattern.CASE_INSENSITIVE);

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

    private static List<OcrNewTableDto> sanitizeBomDescriptions(List<OcrNewTableDto> tables) {
        if (tables == null || tables.isEmpty()) return tables;
        Pattern hangerPat = Pattern.compile("(?i)hanger\\s*loop");
        Pattern supplierTail = Pattern.compile("(?i)\\b(?:s\\s+)?(?:Trading|CO\\.?|Ltd\\.?|Garments|Accessories)\\b.*$");
        for (OcrNewTableDto t : tables) {
            if (t == null) continue;
            List<List<String>> rows = t.getRows();
            if (rows == null || rows.size() < 2) continue;
            List<String> header = rows.get(0);
            if (header == null || header.size() < 6) continue;
            String h0 = safe(header.get(0)).trim();
            String h2 = safe(header.get(2)).trim();
            String h3 = safe(header.get(3)).trim();
            String h4 = safe(header.get(4)).trim();
            boolean looksBom = "Position".equalsIgnoreCase(h0) && "Description".equalsIgnoreCase(h3) && "Composition".equalsIgnoreCase(h4);
            if (!looksBom) continue;

            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row == null || row.size() < 6) continue;
                String type = safe(row.get(2));
                String desc = safe(row.get(3));
                String before = desc;
                // Strip supplier tail noise if it bled into Description
                if (!desc.isBlank()) {
                    desc = supplierTail.matcher(desc).replaceFirst("").trim();
                }
                // Always normalize/dedup BOM Description before returning to frontend.
                // This fixes fabric rows where OCR/hOCR accumulation causes repetitions.
                if (!desc.isBlank()) {
                    desc = TableParser.normalizeBomDescriptionValue(desc);
                }
                {
                    String low = desc.toLowerCase(Locale.ROOT);
                    if (low.contains("zcx") && low.contains("circulose")) {
                        String b = before == null ? "" : before;
                        if (!oneLine(b).equals(oneLine(desc))) {
                            log.info("[BOM-SANITIZE] row#{} desc: '{}' -> '{}'", r, truncate(oneLine(b), 220), truncate(oneLine(desc), 220));
                        }
                    }
                }
                if (hangerPat.matcher(desc).find() || type.toLowerCase(Locale.ROOT).contains("tape")) {
                    if (hangerPat.matcher(desc).find()) {
                        desc = "hanger loop";
                    }
                }
                row.set(3, oneLine(desc));
            }
        }
        return tables;
    }

    private static List<Map<String, String>> extractSalesOrderDetailSizeBreakdownFromLines(
            List<OcrNewLine> lines,
            Map<String, String> formFields
    ) {
        List<Map<String, String>> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : lines) {
            if (l == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        String globalColor = firstNonBlank(
                formFields == null ? null : formFields.get("Colour Name"),
                formFields == null ? null : formFields.get("Color Name"),
                formFields == null ? null : formFields.get("Description")
        );

        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            List<OcrNewLine> pageLines = e.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;
            pageLines.sort(Comparator.comparingInt(OcrNewLine::getTop).thenComparingInt(OcrNewLine::getLeft));

            List<String> texts = mergeLinesByVisualRow(pageLines, 25);

            int idxHeader = indexOfLineContaining(texts, "size / colour breakdown");
            if (idxHeader < 0) continue;

            int pageOutStart = out.size();

            String destinationCountry = "";
            // Ambil baris pertama non-blank setelah header sebagai Country of Destination,
            // kecuali baris metadata yang jelas. Jika baris berikutnya mengandung kode pasar
            // seperti (PM-XX), gabungkan keduanya.
            for (int i = idxHeader + 1; i < Math.min(texts.size(), idxHeader + 12); i++) {
                String t = oneLine(texts.get(i));
                if (t.isBlank()) continue;
                String lower = t.toLowerCase(Locale.ROOT);
                if (lower.startsWith("article no") || lower.startsWith("h&m colour") || lower.startsWith("colour name")) {
                    // lewati metadata awal artikel/warna
                    continue;
                }
                destinationCountry = t;
                // Cek 1 baris berikutnya untuk kode pasar agar tidak terpisah oleh OCR
                if (i + 1 < texts.size()) {
                    String t2 = oneLine(texts.get(i + 1));
                    String up2 = t2.toUpperCase(Locale.ROOT);
                    if (!t2.isBlank() && (up2.contains("(PM") || up2.matches(".*\\bPM[- ]?[A-Z]{2,}.*"))) {
                        destinationCountry = destinationCountry + " " + t2;
                    }
                }
                break;
            }

            String color = globalColor;
            if (color == null || color.isBlank()) color = "";

            @SuppressWarnings("unchecked")
            final Map<String, String>[] currentRow = (Map<String, String>[]) new Map[]{null};
            final boolean[] sawAnySize = new boolean[]{false};

            for (int i = idxHeader; i < texts.size(); i++) {
                String t = oneLine(texts.get(i));
                if (t.isBlank()) continue;

                String lower = t.toLowerCase(Locale.ROOT);
                if (lower.startsWith("bill of material")) break;

                // Some pages contain multiple destination country sections; detect mid-page switches
                if (looksLikeDestinationCountryLine(t)) {
                    // If there is an active row, flush it before switching context
                    if (currentRow[0] != null && (sawAnySize[0] || currentRow[0].containsKey("total") || currentRow[0].containsKey("type"))) {
                        ensureSizeDefaults(currentRow[0]);
                        out.add(currentRow[0]);
                    }
                    currentRow[0] = null;
                    sawAnySize[0] = false;
                    destinationCountry = t;
                    continue;
                }

                if (lower.equals("assortment") || lower.startsWith("assortment ") ||
                        lower.equals("solid") || lower.startsWith("solid ") ||
                        lower.equals("total") || lower.startsWith("total ")) {
                    if (currentRow[0] != null && (sawAnySize[0] || currentRow[0].containsKey("total") || currentRow[0].containsKey("type"))) {
                        ensureSizeDefaults(currentRow[0]);
                        out.add(currentRow[0]);
                    }
                    String type = lower.startsWith("assortment") ? "Assortment" : (lower.startsWith("solid") ? "Solid" : "Total");
                    currentRow[0] = new LinkedHashMap<>();
                    sawAnySize[0] = false;
                    if (!type.isBlank()) currentRow[0].put("type", type);
                    if (!color.isBlank()) currentRow[0].put("color", color);
                    if (!destinationCountry.isBlank()) {
                        currentRow[0].put("destinationCountry", destinationCountry);
                        currentRow[0].put("countryOfDestination", destinationCountry);
                    }
                    continue;
                }

                if (currentRow[0] == null) continue;

                Matcher qm = QUANTITY_LINE.matcher(t);
                if (qm.matches()) {
                    String q = normalizeNumber(qm.group(1));
                    if (q != null && !q.isBlank()) currentRow[0].put("total", q);
                    ensureSizeDefaults(currentRow[0]);
                    out.add(currentRow[0]);
                    currentRow[0] = null;
                    sawAnySize[0] = false;
                    continue;
                }

                // Capture 'No of Asst:' value and attach to current Assortment row
                if (lower.startsWith("no of asst")) {
                    String n = parseInlineOrNextNumber(t, texts, i + 1);
                    if (n != null && !n.isBlank()) {
                        if (currentRow[0] != null) {
                            currentRow[0].put("noOfAsst", n);
                        } else {
                            // If the Assortment row was already emitted (e.g., upon 'Quantity:'),
                            // backfill into the last Assortment row for this page.
                            for (int ri = out.size() - 1; ri >= pageOutStart; ri--) {
                                Map<String, String> r = out.get(ri);
                                if ("Assortment".equals(r.getOrDefault("type", ""))) {
                                    r.put("noOfAsst", n);
                                    break;
                                }
                            }
                        }
                    }
                    continue;
                }

                // Fallback: bare "Quantity:" without a parseable number — still emit the row
                if (lower.startsWith("quantity") || lower.startsWith("qty")) {
                    ensureSizeDefaults(currentRow[0]);
                    out.add(currentRow[0]);
                    currentRow[0] = null;
                    sawAnySize[0] = false;
                    continue;
                }

                // Lines like: "XS (XS)* 236", "L(L)y* 622"
                Matcher sm = SIZE_VALUE_LINE.matcher(t);
                if (sm.matches()) {
                    String sizeRaw = sm.group(1);
                    String v = normalizeNumber(sm.group(2));
                    String size = normalizeSizeKey(sizeRaw);
                    if (size != null && v != null && !v.isBlank()) {
                        currentRow[0].put(size, v);
                        sawAnySize[0] = true;
                    }
                }
            }

            if (currentRow[0] != null && (sawAnySize[0] || currentRow[0].containsKey("total") || currentRow[0].containsKey("type"))) {
                ensureSizeDefaults(currentRow[0]);
                out.add(currentRow[0]);
            }

            fillMissingAssortmentValues(out, pageOutStart, texts);
        }

        return out;
    }

    private static void fillMissingAssortmentValues(
            List<Map<String, String>> rows, int startIdx, List<String> texts) {
        if (rows == null || startIdx >= rows.size()) return;

        // Page-level hints (used only when they fit logically within a segment)
        Integer qtyAssortPage = null;
        String noOfAsstFoundPage = null;
        for (String t : texts) {
            String lower = t.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("quantity")) {
                Matcher m = Pattern.compile("(\\d[\\d\\s]*?)\\s*$").matcher(t);
                if (m.find()) {
                    try { qtyAssortPage = Integer.parseInt(m.group(1).replaceAll("\\s+", "")); }
                    catch (NumberFormatException ignored) {}
                }
            } else if (lower.startsWith("no of asst")) {
                Matcher m = Pattern.compile("(\\d[\\d\\s]*?)\\s*$").matcher(t);
                if (m.find()) {
                    noOfAsstFoundPage = normalizeNumber(m.group(1));
                }
            }
        }

        // Process per destinationCountry segment within this page
        int i = startIdx;
        while (i < rows.size()) {
            // Determine segment key (destinationCountry)
            String segCountry = firstNonBlank(
                    rows.get(i).get("destinationCountry"),
                    rows.get(i).get("countryOfDestination"));

            int segStart = i;
            int segEnd = i;
            for (int j = i; j < rows.size(); j++) {
                String c = firstNonBlank(rows.get(j).get("destinationCountry"), rows.get(j).get("countryOfDestination"));
                if (!Objects.equals(c, segCountry)) { break; }
                segEnd = j;
            }

            // Within the segment, try to backfill the first Assortment that has no meaningful sizes
            Map<String, String> assortment = null, solid = null, total = null;
            for (int j = segStart; j <= segEnd; j++) {
                Map<String, String> r = rows.get(j);
                String type = r.getOrDefault("type", "");
                if ("Assortment".equals(type) && assortment == null) assortment = r;
                else if ("Solid".equals(type)) solid = r; // keep last Solid in the segment
                else if ("Total".equals(type)) total = r; // keep last Total in the segment
            }

            if (assortment != null && solid != null && total != null) {
                // Backfill No of Asst if missing
                if (!assortment.containsKey("noOfAsst") && noOfAsstFoundPage != null && !noOfAsstFoundPage.isBlank()) {
                    assortment.put("noOfAsst", noOfAsstFoundPage);
                }

                List<String> sizes = List.of("XS", "S", "M", "L", "XL");
                boolean hasPositive = false;
                for (String sz : sizes) {
                    String v = assortment.get(sz);
                    if (v != null && !v.isBlank()) {
                        try {
                            if (Integer.parseInt(v.replaceAll("\\s+", "")) > 0) { hasPositive = true; break; }
                        } catch (NumberFormatException ignored) { hasPositive = true; break; }
                    }
                }

                if (!hasPositive) {
                    int gcd = 0;
                    int[] diffs = new int[sizes.size()];
                    for (int k = 0; k < sizes.size(); k++) {
                        String sz = sizes.get(k);
                        try {
                            int tVal = Integer.parseInt(total.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                            int sVal = Integer.parseInt(solid.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                            int diff = Math.max(0, tVal - sVal);
                            diffs[k] = diff;
                            if (diff > 0) gcd = (gcd == 0) ? diff : gcd(gcd, diff);
                        } catch (NumberFormatException ignored) {}
                    }

                    if (gcd > 0) {
                        int divisor = gcd;
                        if (qtyAssortPage != null && qtyAssortPage > 0) {
                            boolean allDivisible = true;
                            for (int d : diffs) { if (d % qtyAssortPage != 0) { allDivisible = false; break; } }
                            if (allDivisible) {
                                int sumIfQty = 0;
                                for (int d : diffs) sumIfQty += d / qtyAssortPage;
                                if (sumIfQty == qtyAssortPage) {
                                    divisor = qtyAssortPage;
                                }
                            }
                        }

                        int sum = 0;
                        for (int k = 0; k < sizes.size(); k++) {
                            int val = (divisor > 0) ? (diffs[k] / divisor) : 0;
                            if (val < 0) val = 0;
                            if (val > 0) sum += val;
                            assortment.put(sizes.get(k), String.valueOf(val));
                        }
                        assortment.put("total", String.valueOf(sum));
                    }
                }
            }

            i = segEnd + 1;
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    private static int indexOfLineContaining(List<String> texts, String needle) {
        if (texts == null || texts.isEmpty() || needle == null || needle.isBlank()) return -1;
        String n = needle.toLowerCase(Locale.ROOT);
        for (int i = 0; i < texts.size(); i++) {
            String t = oneLine(texts.get(i)).toLowerCase(Locale.ROOT);
            if (t.contains(n)) return i;
        }
        return -1;
    }

    private static String parseInlineOrNextNumber(String current, List<String> texts, int nextIdx) {
        if (current != null) {
            Matcher m = Pattern.compile("(\\d[\\d\\s]*?)\\s*$").matcher(current);
            if (m.find()) return normalizeNumber(m.group(1));
        }
        if (texts != null && nextIdx >= 0 && nextIdx < texts.size()) {
            String nxt = oneLine(texts.get(nextIdx));
            if (nxt != null && nxt.matches("\\s*\\d[\\d\\s]*\\s*")) {
                return normalizeNumber(nxt);
            }
        }
        return null;
    }

    private static void ensureSizeDefaults(Map<String, String> row) {
        if (row == null) return;
        String type = row.get("type");
        if (type == null) return;
        if (!("Assortment".equals(type) || "Solid".equals(type) || "Total".equals(type))) return;

        List<String> sizes = List.of("XS", "S", "M", "L", "XL");
        int sum = 0;
        boolean anyNumeric = false;
        for (String sz : sizes) {
            String v = row.get(sz);
            if (v == null || v.isBlank()) {
                row.put(sz, "0");
                v = "0";
            }
            try {
                int iv = Integer.parseInt(v.replaceAll("\\s+", ""));
                sum += iv;
                anyNumeric = true;
            } catch (NumberFormatException ignored) {}
        }
        // If total missing or blank: set computed sum if we had any numeric values; otherwise default to 0
        String tot = row.get("total");
        if (tot == null || tot.isBlank()) {
            row.put("total", String.valueOf(anyNumeric ? sum : 0));
        }
    }

    private static boolean looksLikeDestinationCountryLine(String t) {
        if (t == null) return false;
        String s = oneLine(t);
        if (s.isBlank()) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("article no")) return false;
        if (lower.startsWith("h&m colour")) return false;
        if (lower.startsWith("colour name")) return false;
        // Exclude obvious size or section lines
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.matches("^(XS|S|M|L|XL)\\s*\\(.*")) return false;
        if (upper.startsWith("ASSORTMENT") || upper.startsWith("SOLID") || upper.startsWith("TOTAL")) return false;

        // Safer heuristic: country lines in these H&M docs always include a market code with 'PM'
        // e.g., "Canada CA (PM-CA)", "Colombia (Stores H&M) CO (PM-CO)", "Germany (Central Eur Market) DE (PMCEU)"
        return s.contains("(") && s.contains(")") && upper.contains("(PM");
    }

    private static String normalizeSizeKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.equals("XS")) return "XS";
        if (s.equals("XL")) return "XL";
        if (s.equals("S")) return "S";
        if (s.equals("M")) return "M";
        if (s.equals("L")) return "L";
        return null;
    }

    private static String normalizeNumber(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "").trim();
    }

    private static List<String> mergeLinesByVisualRow(List<OcrNewLine> sortedLines, int yTolerance) {
        List<String> result = new ArrayList<>();
        if (sortedLines == null || sortedLines.isEmpty()) return result;
        int idx = 0;
        while (idx < sortedLines.size()) {
            OcrNewLine base = sortedLines.get(idx);
            int baseTop = base.getTop();
            List<OcrNewLine> rowGroup = new ArrayList<>();
            rowGroup.add(base);
            idx++;
            while (idx < sortedLines.size()) {
                OcrNewLine next = sortedLines.get(idx);
                if (Math.abs(next.getTop() - baseTop) <= yTolerance) {
                    rowGroup.add(next);
                    idx++;
                } else {
                    break;
                }
            }
            rowGroup.sort(Comparator.comparingInt(OcrNewLine::getLeft));
            StringBuilder merged = new StringBuilder();
            for (OcrNewLine l : rowGroup) {
                String t = l.getText();
                if (t != null && !t.isBlank()) {
                    if (merged.length() > 0) merged.append(' ');
                    merged.append(t.trim());
                }
            }
            String m = merged.toString().trim();
            if (!m.isEmpty()) result.add(m);
        }
        return result;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return "";
        for (String x : xs) {
            if (x == null) continue;
            String t = x.trim();
            if (!t.isBlank()) return t;
        }
        return "";
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file) {
        return analyzeDocument(file, null, true);  // hOCR default for better fragment handling
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file, Boolean debugOverride) {
        return analyzeDocument(file, debugOverride, true);  // hOCR default for better fragment handling
    }

    /**
     * Analyze document with optional hOCR mode for better fragment handling.
     * 
     * @param file The uploaded file
     * @param debugOverride Enable debug logging
     * @param useHocr Use hOCR extraction for better handling of split words
     */
    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file, Boolean debugOverride, boolean useHocr) {
        validateFile(file);

        boolean effectiveDebug = debugLogging || Boolean.TRUE.equals(debugOverride);

        try {
            byte[] fileBytes = file.getBytes();

            if (effectiveDebug) {
                log.info("[OCR-NEW][DEBUG] analyzeDocument start: fileName={}, contentType={}, sizeBytes={}, renderDpi={}, debugOverride={}",
                        file.getOriginalFilename(), file.getContentType(), fileBytes.length, renderDpi, debugOverride);
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

            if (effectiveDebug) {
                log.info("[OCR-NEW][DEBUG] rendered pages: pageCount={}, useHocr={}", pageImages.size(), useHocr);
                for (int i = 0; i < Math.min(pageImages.size(), 5); i++) {
                    BufferedImage img = pageImages.get(i);
                    log.info("[OCR-NEW][DEBUG] pageImage[{}]: width={}, height={}", i, img.getWidth(), img.getHeight());
                }
                if (pageImages.size() > 5) {
                    log.info("[OCR-NEW][DEBUG] pageImage: {} more pages not logged", pageImages.size() - 5);
                }
            }

            List<OcrNewLine> allLines = new ArrayList<>();
            List<OcrNewLine> rawLinesForTables = useHocr ? new ArrayList<>() : allLines;
            for (int i = 0; i < pageImages.size(); i++) {
                if (useHocr) {
                    // Use hOCR extraction with enhanced fragment merging
                    allLines.addAll(ocrEngine.extractLinesWithHocr(pageImages.get(i), i));
                    rawLinesForTables.addAll(ocrEngine.extractLinesFromImage(pageImages.get(i), i));
                } else {
                    // Use standard word-level extraction
                    allLines.addAll(ocrEngine.extractLinesFromImage(pageImages.get(i), i));
                }
            }

            allLines.sort(Comparator
                    .comparingInt(OcrNewLine::getPage)
                    .thenComparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            if (effectiveDebug) {
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
            List<OcrNewTableDto> tables;
            if (useHocr) {
                rawLinesForTables.sort(Comparator
                        .comparingInt(OcrNewLine::getPage)
                        .thenComparingInt(OcrNewLine::getTop)
                        .thenComparingInt(OcrNewLine::getLeft));
                List<OcrNewTableDto> rawTables = tableParser.parseTables(rawLinesForTables);
                List<OcrNewTableDto> hocrTables = tableParser.parseTables(allLines);
                tables = mergeBomTablesPreferHocrLongText(rawTables, hocrTables);
            } else {
                tables = tableParser.parseTables(allLines);
            }

            // Final hard guard: sanitize hanger loop Description and strip supplier noise before returning
            tables = sanitizeBomDescriptions(tables);

            // Extract using both methods; prefer line-based when available because it carries
            // richer context (destinationCountry, type, noOfAsst). Fallback to table-based otherwise.
            List<Map<String, String>> tableDetail = extractSalesOrderDetailSizeBreakdown(tables);
            List<Map<String, String>> lineDetail = extractSalesOrderDetailSizeBreakdownFromLines(allLines, formFields);
            List<Map<String, String>> salesOrderDetailSizeBreakdown = !lineDetail.isEmpty() ? lineDetail : tableDetail;

            String extractedText = allLines.stream()
                    .map(OcrNewLine::getText)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

            if (effectiveDebug) {
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
                            String rowText = row == null ? "" : row.stream()
                                    .filter(Objects::nonNull)
                                    .map(OcrNewService::oneLine)
                                    .map(s -> truncate(s, 80))
                                    .reduce("", (a, b) -> a.isEmpty() ? b : a + " | " + b);
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
                    .salesOrderDetailSizeBreakdown(salesOrderDetailSizeBreakdown)
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String oneLine(String s) {
        return safe(s).replaceAll("\\s+", " ").trim();
    }

    private static float round1(float v) {
        return Math.round(v * 10f) / 10f;
    }

    private static Float round1(Float v) {
        if (v == null) return null;
        return round1(v.floatValue());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.OCR_INVALID_FILE, "파일이 비어있습니다");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.OCR_INVALID_FILE,
                    "지원하는 파일 형식: PNG, JPG, JPEG, PDF");
        }
    }

    private static List<OcrNewTableDto> mergeBomTablesPreferHocrLongText(List<OcrNewTableDto> rawTables, List<OcrNewTableDto> hocrTables) {
        if (hocrTables == null || hocrTables.isEmpty()) return rawTables == null ? List.of() : rawTables;

        OcrNewTableDto rawBom = findBomTable(rawTables);
        OcrNewTableDto hocrBom = findBomTable(hocrTables);
        if (rawBom == null || hocrBom == null) return hocrTables;

        List<List<String>> rawRows = rawBom.getRows();
        List<List<String>> hocrRows = hocrBom.getRows();
        if (rawRows == null || rawRows.isEmpty()) return hocrTables;

        List<List<String>> mergedRows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            List<String> rr = rawRows.get(i);
            if (rr == null) continue;
            List<String> out = new ArrayList<>(rr);

            if (hocrRows != null && i < hocrRows.size()) {
                List<String> hr = hocrRows.get(i);
                if (hr != null && hr.size() >= 6 && out.size() >= 6) {
                    String hDesc = safe(hr.get(3)).trim();
                    String hComp = safe(hr.get(4)).trim();
                    String rDesc = safe(out.get(3)).trim();
                    String rComp = safe(out.get(4)).trim();

                    // Prefer the LONGER text between raw and hOCR for Description/Composition.
                    // hOCR can lose page-break continuation content (e.g., fabric row page-2 overflow),
                    // while raw OCR preserves line-level joined content. When both meet the minimum
                    // length, pick the longer one to retain more information.
                    if (hDesc.length() >= 35 && hDesc.length() >= rDesc.length()) {
                        out.set(3, hDesc);
                    } else if (rDesc.length() < 35 && hDesc.length() >= 35) {
                        out.set(3, hDesc);
                    }
                    if (hComp.length() >= 35 && hComp.length() >= rComp.length()) {
                        out.set(4, hComp);
                    } else if (rComp.length() < 35 && hComp.length() >= 35) {
                        out.set(4, hComp);
                    }
                }
            }

            mergedRows.add(out);
        }

        OcrNewTableDto mergedBom = OcrNewTableDto.builder()
                .page(hocrBom.getPage())
                .index(hocrBom.getIndex())
                .rowCount(mergedRows.size())
                .columnCount(hocrBom.getColumnCount())
                .cells(hocrBom.getCells())
                .rows(mergedRows)
                .build();

        List<OcrNewTableDto> outTables = new ArrayList<>();
        for (OcrNewTableDto t : hocrTables) {
            if (t == null) continue;
            outTables.add(t == hocrBom ? mergedBom : t);
        }
        return outTables;
    }

    private static OcrNewTableDto findBomTable(List<OcrNewTableDto> tables) {
        if (tables == null) return null;
        for (OcrNewTableDto t : tables) {
            if (t == null) continue;
            List<List<String>> rows = t.getRows();
            if (rows == null || rows.isEmpty()) continue;
            List<String> header = rows.get(0);
            if (header == null || header.size() < 6) continue;

            String h0 = safe(header.get(0)).trim();
            String h3 = safe(header.get(3)).trim();
            String h4 = safe(header.get(4)).trim();
            if ("Position".equalsIgnoreCase(h0) && "Description".equalsIgnoreCase(h3) && "Composition".equalsIgnoreCase(h4)) {
                return t;
            }
        }
        return null;
    }

    private boolean isPdf(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType != null && PDF_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    private static List<Map<String, String>> extractSalesOrderDetailSizeBreakdown(List<OcrNewTableDto> tables) {
        List<Map<String, String>> out = new ArrayList<>();
        if (tables == null || tables.isEmpty()) return out;

        for (OcrNewTableDto t : tables) {
            List<List<String>> rows = t.getRows();
            if (rows == null || rows.size() < 2) continue;
            if (!looksLikeSalesOrderDetailSizeBreakdown(rows)) continue;

            List<String> header = rows.get(0);
            int iColor = idxOf(header, h -> eqAny(h, "color", "colour") || h.contains("color") || h.contains("colour"));
            int iXS = idxOf(header, h -> h.equals("xs") || h.endsWith(" xs") || h.startsWith("xs "));
            int iS = idxOf(header, h -> h.equals("s"));
            int iM = idxOf(header, h -> h.equals("m"));
            int iL = idxOf(header, h -> h.equals("l"));
            int iXL = idxOf(header, h -> h.equals("xl") || h.endsWith(" xl") || h.startsWith("xl "));
            int iTotal = idxOf(header, h -> h.equals("total") || h.contains("total"));

            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row == null || row.stream().allMatch(v -> v == null || v.isBlank())) continue;

                Map<String, String> m = new LinkedHashMap<>();
                putIfNonBlank(m, "color", cell(row, iColor));
                putIfNonBlank(m, "XS", cell(row, iXS));
                putIfNonBlank(m, "S", cell(row, iS));
                putIfNonBlank(m, "M", cell(row, iM));
                putIfNonBlank(m, "L", cell(row, iL));
                putIfNonBlank(m, "XL", cell(row, iXL));
                putIfNonBlank(m, "total", cell(row, iTotal));

                // Try to infer row type from previous non-empty or keep as generic; still ensure size defaults
                if (!m.isEmpty()) {
                    ensureSizeDefaults(m);
                    out.add(m);
                }
            }
        }

        return out;
    }

    private static boolean looksLikeSalesOrderDetailSizeBreakdown(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) return false;
        List<String> header = rows.get(0);
        if (header == null || header.isEmpty()) return false;

        List<String> h = header.stream().map(OcrNewService::normHeader).toList();
        boolean hasColor = h.stream().anyMatch(x -> eqAny(x, "color", "colour") || x.contains("color") || x.contains("colour"));
        boolean hasXS = h.stream().anyMatch(x -> x.equals("xs") || x.endsWith(" xs") || x.startsWith("xs "));
        boolean hasS = h.stream().anyMatch(x -> x.equals("s"));
        boolean hasM = h.stream().anyMatch(x -> x.equals("m"));
        boolean hasL = h.stream().anyMatch(x -> x.equals("l"));
        boolean hasXL = h.stream().anyMatch(x -> x.equals("xl") || x.endsWith(" xl") || x.startsWith("xl "));
        boolean hasTotal = h.stream().anyMatch(x -> x.equals("total") || x.contains("total"));
        return hasColor && hasXS && hasS && hasM && hasL && hasXL && hasTotal;
    }

    private static int idxOf(List<String> header, Predicate<String> pred) {
        if (header == null) return -1;
        for (int i = 0; i < header.size(); i++) {
            String h = normHeader(header.get(i));
            if (pred.test(h)) return i;
        }
        return -1;
    }

    private static String normHeader(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean eqAny(String s, String... xs) {
        if (s == null) return false;
        for (String x : xs) {
            if (s.equals(x)) return true;
        }
        return false;
    }

    private static String cell(List<String> row, int idx) {
        if (row == null) return "";
        if (idx < 0 || idx >= row.size()) return "";
        return Optional.ofNullable(row.get(idx)).orElse("").trim();
    }

    private static void putIfNonBlank(Map<String, String> m, String k, String v) {
        if (m == null || k == null || v == null) return;
        String t = v.trim();
        if (!t.isBlank()) m.put(k, t);
    }
}
