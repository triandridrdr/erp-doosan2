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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
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

    // Allow trailing ) or other chars after the number (OCR artifacts)
    private static final Pattern SIZE_VALUE_LINE = Pattern.compile("^\\s*([A-Za-z]{1,2})\\s*(?:\\(|\\b).*?\\b(\\d[\\d\\s]{0,12})\\s*\\)?\\s*$");
    private static final Pattern SIZE_VALUE_LINE_PARENS = Pattern.compile("^.*?\\(\\s*([A-Za-z]{1,2}(?:\\s*\\/\\s*P)?)\\s*\\).*?\\b(\\d[\\d\\s]{0,12})\\s*\\)?\\s*$");
    // Flexible fallback: finds size label followed by parenthetical size format and a number
    // Matches patterns like "XL (XL/P)* 41" even with OCR artifacts
    private static final Pattern SIZE_VALUE_LINE_FLEX = Pattern.compile("(?i)\\b(X[SL]|S|M|L)\\s*\\([^)]+\\)[^\\d]*(\\d+)\\s*\\)?\\s*$");
    // Pattern for XL lines where OCR misreads numbers as letters (e.g., "41" -> "A" or "4l")
    private static final Pattern SIZE_VALUE_LINE_OCR_FIX = Pattern.compile("(?i)^\\s*(X[SL]|XS)\\s*\\([^)]+\\)\\*?\\s*([A-Za-z0-9]+)\\)?\\s*$");
    private static final Pattern QUANTITY_LINE = Pattern.compile("^\\s*(?:quantity|qty)\\s*[:#]?\\s*(\\d[\\d\\s]{0,15})\\s*$", Pattern.CASE_INSENSITIVE);
    // Matches an H&M destination code in parentheses: "(PMSCA)", "(PM-UK)", "(PM-TR)", "(OLNAM)".
    // Requires 2 uppercase letters + 1+ uppercase/digit/hyphen. Closing ')' optional (OCR may drop it).
    // Safely excludes size patterns like "(XS)", "(M)", "(EP)" (too short / fails the trailing +).
    private static final Pattern DEST_COUNTRY_PAT =
            Pattern.compile("\\([A-Z]{2}[A-Z0-9\\-]+\\)?");
    // Accept PM market codes even when OCR removes parentheses or inserts spaces.
    private static final Pattern DEST_COUNTRY_CODE_PAT =
            Pattern.compile("\\bPM[- ]?[A-Z0-9]{2,}\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern COUNTRY_CODE_TOKEN_PAT =
            Pattern.compile("(?<![A-Z0-9])[A-Z]{2}(?![A-Z0-9])");

    private static final Set<String> PO_DELIVERY_COUNTRY_CODES = Set.of(
            "SE", "GB", "DE", "BE", "US", "PL", "JP", "KR", "CH", "CA", "TR", "MX", "MY", "ME", "IX",
            "TH", "RS", "ID", "PH", "IN", "CO", "VN", "PA", "EC"
    );

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

    /**
     * Fallback extractor parsing raw OCR lines if table detection fails.
     * Heuristics:
     * - Locate a line containing 'Colour / Country Breakdown' OR a header line containing both 'Country' and 'Total'.
     * - Collect subsequent lines that contain a PM code (PM-XX / PMXX) and at least one number; use the last number as total.
     * - Country code is taken as the first two-letter uppercase token or first token before PM code.
     */
    private static List<Map<String, String>> extractTotalCountryBreakdownFromLines(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        // Build flattened, normalized text list
        List<String> normLines = new ArrayList<>(allLines.size());
        for (OcrNewLine l : allLines) normLines.add(oneLine(l.getText()));

        int startIdx = -1;
        for (int i = 0; i < normLines.size(); i++) {
            String s = normLines.get(i).toLowerCase(Locale.ROOT);
            if (s.contains("colour / country breakdown") || (s.contains("country") && s.contains("total"))) {
                startIdx = i;
                break;
            }
        }

        if (startIdx < 0) {
            // No obvious header; still try scanning entire doc for PM lines with totals
            startIdx = 0;
        }

        // Accept codes like PM-UK, PMUK, OL-UK, OLUK, OLEEU, etc. Code is REQUIRED to avoid false positives.
        Pattern codePat = Pattern.compile("\\b(?:PM|OL)[- ]?[A-Z0-9]{2,6}\\b", Pattern.CASE_INSENSITIVE);
        // Prefer stricter 2-3 letter destination after PM/OL (e.g., OL-IN, PM-UK)
        Pattern codeStrictPat = Pattern.compile("\\b(?:PM|OL)[- ]?[A-Z]{2,3}\\b", Pattern.CASE_INSENSITIVE);
        Pattern numPat = Pattern.compile("(?<![A-Za-z])\\d[\\d\\s.,]*");
        // Country token at start, allow 2-3 uppercase letters (e.g., OE, OL, OLE)
        Pattern country2Pat = Pattern.compile("^([A-Z]{2,3})\\b");

        for (int i = startIdx; i < normLines.size(); i++) {
            String line = normLines.get(i);
            String low = line.toLowerCase(Locale.ROOT);
            // Stop on unrelated big sections
            if (low.startsWith("bill of material") || low.startsWith("labels") || low.startsWith("production units")) {
                break;
            }

            // Skip obvious non-row lines
            if (low.contains("order no") || low.contains("product no") || low.contains("product name")
                    || low.contains("date of order") || low.contains("supplier code") || low.contains("season:")
                    || low.contains("supplier name") || low.startsWith("created:") || low.startsWith("page:")) {
                continue;
            }

            // Try to detect a code AFTER the leading country token if present
            int searchFrom = 0;
            Matcher c2peek = country2Pat.matcher(line);
            if (c2peek.find()) searchFrom = c2peek.end();

            String pmCode = "";
            Matcher strictFrom = codeStrictPat.matcher(line.substring(Math.min(searchFrom, line.length())));
            if (strictFrom.find()) {
                pmCode = strictFrom.group();
            } else {
                Matcher codeM = codePat.matcher(line.substring(Math.min(searchFrom, line.length())));
                if (codeM.find()) pmCode = codeM.group();
            }
            if (pmCode.isBlank()) continue; // require a destination code to qualify this line
            pmCode = pmCode.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            // Normalize missing hyphen: OLIN -> OL-IN
            if ((pmCode.startsWith("OL") || pmCode.startsWith("PM")) && !pmCode.contains("-") && pmCode.length() >= 4) {
                pmCode = pmCode.substring(0, 2) + "-" + pmCode.substring(2);
            }
            // Validate suffix of code (country part) to avoid tokens like 'OLOL' (duplicated prefix)
            String suffix = pmCode.replaceFirst("^(OL|PM)-?", "");
            if (suffix.equals("OL") || suffix.equals("PM") || suffix.length() < 2 || suffix.length() > 3) {
                continue; // skip malformed code
            }

            // Extract all numeric tokens; use last as total
            List<String> nums = new ArrayList<>();
            Matcher numM = numPat.matcher(line);
            while (numM.find()) {
                String v = numM.group().trim();
                if (!v.isBlank()) nums.add(v);
            }
            if (nums.isEmpty()) continue;
            // If last two numeric tokens are equal (ignoring group separators), keep one to avoid '1234 1234'
            String last = nums.get(nums.size() - 1);
            String penult = nums.size() >= 2 ? nums.get(nums.size() - 2) : null;
            String lastN = normalizeNumberToken(last);
            String penN = penult != null ? normalizeNumberToken(penult) : null;
            String chosen = (penN != null && penN.equals(lastN)) ? penult : last;
            String total = normalizeNumberToken(chosen);

            // Country: prefer leading 2-letter token; else use token immediately before PM code if present
            String country = "";
            Matcher c2 = country2Pat.matcher(line);
            if (c2.find()) {
                country = c2.group(1);
            } else {
                int pmIdx = line.indexOf(pmCode);
                if (pmIdx > 0) {
                    String before = line.substring(0, pmIdx).trim();
                    // Take last token before pm
                    String[] toks = before.split("\\s+");
                    if (toks.length > 0) {
                        String cand = toks[toks.length - 1].replaceAll("[^A-Za-z]", "");
                        if (cand.length() >= 2 && cand.length() <= 3) country = cand.toUpperCase(Locale.ROOT);
                    }
                }
            }

            Map<String, String> m = new LinkedHashMap<>();
            m.put("country", country);
            if (!pmCode.isBlank()) m.put("pmCode", pmCode);
            m.put("total", total);

            out.add(m);

            if (log.isDebugEnabled() && out.size() <= 3) {
                log.debug("[TCB-LINES][ROW{}] {}", out.size() - 1, m);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[TCB-LINES][SUMMARY] rowsParsed={}", out.size());
        }
        return out;
    }

    private static List<Map<String, String>> extractSalesSampleTermsByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();

            List<OcrNewLine> sorted = new ArrayList<>(pageLines);
            sorted.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            // Only extract from Sales Sample pages (avoid Purchase Order pages which also have "Article No" tables)
            boolean isSalesSamplePage = false;
            for (OcrNewLine l : sorted) {
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.contains("sales sample")
                        || (low.contains("purchase order") && low.contains("sales sample"))
                        || low.contains("sales sample terms")
                        || low.contains("sales samples")) {
                    isSalesSamplePage = true;
                    break;
                }
            }
            if (!isSalesSamplePage) continue;

            pageLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            int headerIdx = -1;
            for (int i = 0; i < pageLines.size(); i++) {
                String s = oneLine(pageLines.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.equals("sales sample terms") || low.startsWith("sales sample terms") || low.contains("sales sample terms")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx < 0) continue;

            int headerTop = pageLines.get(headerIdx).getTop();

            List<String> kept = new ArrayList<>();
            Set<String> seenLines = new HashSet<>();

            // Coordinate-based extraction: OCR ordering can be unreliable on multi-block layouts.
            // Determine a vertical band for the Sales Sample section and collect only relevant lines.
            int sectionStartTop = Integer.MAX_VALUE;
            for (OcrNewLine l : pageLines) {
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.contains("purchase order") && low.contains("sales sample")) {
                    sectionStartTop = Math.min(sectionStartTop, l.getTop());
                }
            }
            if (sectionStartTop == Integer.MAX_VALUE) {
                // Fallback: no explicit section title detected; start from top of page.
                sectionStartTop = 0;
            }

            int stopTop = Integer.MAX_VALUE;
            for (OcrNewLine l : pageLines) {
                if (l.getTop() <= headerTop) continue;
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.startsWith("time of delivery") || low.equals("time of delivery")
                        || low.startsWith("article no") || low.contains("article no")
                        || low.contains("destination studio") || low.startsWith("destination studio")) {
                    stopTop = Math.min(stopTop, l.getTop());
                }
            }

            int bandEndTop = stopTop != Integer.MAX_VALUE ? stopTop : (headerTop + 2000);

            List<OcrNewLine> band = new ArrayList<>();
            for (OcrNewLine l : pageLines) {
                if (l.getTop() < sectionStartTop) continue;
                if (l.getTop() >= bandEndTop) continue;
                band.add(l);
            }
            band.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            for (OcrNewLine l : band) {
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);

                if (low.startsWith("sales sample terms")) continue;
                if (low.startsWith("page:")) continue;
                if (low.startsWith("created:")) continue;
                if (low.contains("purchase order") && low.contains("sales sample") && low.length() <= 40) continue;
                if (low.startsWith("by accepting")) continue;
                if (low.startsWith("(i)") || low.startsWith("(ii)") || low.startsWith("(iii)")) continue;
                if (low.contains("conditions apply") || low.contains("standard purchase")) continue;
                if (low.startsWith("sales sample order no")) continue;
                if (low.startsWith("purchase order no")) continue;
                if (low.startsWith("date of order")) continue;
                if (low.startsWith("product description")) continue;
                if (low.startsWith("supplier code")) continue;
                if (low.startsWith("supplier name")) continue;
                if (low.startsWith("type of construction")) continue;
                if (low.startsWith("account number")) continue;
                if (low.startsWith("transport by")) continue;

                boolean looksLikeSalesSampleTerms =
                        low.contains("sales sample")
                                || low.contains("sales samples")
                                || low.contains("hang tag")
                                || low.contains("price tag")
                                || low.contains("qr")
                                || low.contains("attached before shipping")
                                || low.contains("fails to deliver")
                                || low.contains("right to cancel")
                                || low.contains("reimburse")
                                || low.startsWith("the ")
                                || low.startsWith("if ")
                                || low.startsWith("costs ")
                                || low.startsWith("any liability");

                if (!looksLikeSalesSampleTerms) continue;
                if (s.length() < 18) continue;
                if (low.matches("^[a-z\s]{0,18}:.*$")) continue;
                if (low.matches("^\\d{3}\\s+\\d{2}-\\d{3}.*$")) continue;

                String norm = low
                        .replaceAll("\\s+", " ")
                        .replace(" and the supplier shall ", " and shall ")
                        .replace(" and supplier shall ", " and shall ")
                        .replace(" and the supplier ", " and supplier ")
                        .replace(" the supplier ", " supplier ")
                        .replace(" costs for sales samples are included in the total price for above-mentioned ",
                                " costs for sales samples are included in the total price for the above-mentioned ")
                        .trim();

                if (!seenLines.add(norm)) continue;
                kept.add(s);
            }

            if (!kept.isEmpty()) {
                // Remove duplicate "Costs for Sales Samples are included..." variants.
                List<Integer> costIdx = new ArrayList<>();
                for (int i = 0; i < kept.size(); i++) {
                    String k = kept.get(i);
                    if (k == null) continue;
                    String kl = k.toLowerCase(Locale.ROOT);
                    if (kl.contains("costs for sales samples") && kl.contains("included") && kl.contains("total price")) {
                        costIdx.add(i);
                    }
                }
                if (costIdx.size() > 1) {
                    int best = costIdx.get(0);
                    for (int idx : costIdx) {
                        String v = kept.get(idx);
                        if (v == null) continue;
                        String vl = v.toLowerCase(Locale.ROOT);
                        String bl = kept.get(best) == null ? "" : kept.get(best).toLowerCase(Locale.ROOT);
                        boolean vHasThe = vl.contains("the above-mentioned");
                        boolean bHasThe = bl.contains("the above-mentioned");
                        if (vHasThe && !bHasThe) {
                            best = idx;
                            continue;
                        }
                        if (v.length() > (kept.get(best) == null ? 0 : kept.get(best).length())) {
                            best = idx;
                        }
                    }
                    for (int i = costIdx.size() - 1; i >= 0; i--) {
                        int idx = costIdx.get(i);
                        if (idx != best && idx >= 0 && idx < kept.size()) {
                            kept.remove(idx);
                        }
                    }
                }

                // Reorder common continuation lines to match natural paragraph flow.
                int sendTheIdx = -1;
                for (int i = 0; i < kept.size(); i++) {
                    String k = kept.get(i);
                    if (k == null) continue;
                    String kl = k.trim().toLowerCase(Locale.ROOT);
                    if (kl.endsWith("send the") || kl.endsWith("send the,")) {
                        sendTheIdx = i;
                        break;
                    }
                }
                if (sendTheIdx >= 0) {
                    for (int i = sendTheIdx + 1; i < kept.size(); i++) {
                        String k = kept.get(i);
                        if (k == null) continue;
                        String kl = k.trim().toLowerCase(Locale.ROOT);
                        if (kl.startsWith("sales samples ")) {
                            String moved = kept.remove(i);
                            kept.add(sendTheIdx + 1, moved);
                            break;
                        }
                    }
                }

                int qrIdx = -1;
                for (int i = 0; i < kept.size(); i++) {
                    String k = kept.get(i);
                    if (k == null) continue;
                    String kl = k.trim().toLowerCase(Locale.ROOT);
                    if (kl.endsWith(" qr") || kl.endsWith(" qr,")) {
                        qrIdx = i;
                        break;
                    }
                }
                if (qrIdx >= 0) {
                    for (int i = qrIdx + 1; i < kept.size(); i++) {
                        String k = kept.get(i);
                        if (k == null) continue;
                        String kl = k.trim().toLowerCase(Locale.ROOT);
                        if (kl.startsWith("codes (")) {
                            String moved = kept.remove(i);
                            kept.add(qrIdx + 1, moved);
                            break;
                        }
                    }
                }

                // Merge the cancellation sentence continuation:
                // "... without" + "any liability ... cancellation." should be a single paragraph line.
                int withoutIdx = -1;
                for (int i = 0; i < kept.size(); i++) {
                    String k = kept.get(i);
                    if (k == null) continue;
                    String kl = k.trim().toLowerCase(Locale.ROOT);
                    if (kl.endsWith(" without") || kl.endsWith(" without,") || kl.endsWith(" without.")) {
                        withoutIdx = i;
                        break;
                    }
                }

                int anyLiabIdx = -1;
                for (int i = 0; i < kept.size(); i++) {
                    String k = kept.get(i);
                    if (k == null) continue;
                    String kl = k.trim().toLowerCase(Locale.ROOT);
                    if (kl.startsWith("any liability")) {
                        anyLiabIdx = i;
                        break;
                    }
                }

                if (withoutIdx >= 0 && anyLiabIdx >= 0 && withoutIdx != anyLiabIdx) {
                    int secondIdx = anyLiabIdx;
                    if (withoutIdx + 1 == anyLiabIdx) {
                        secondIdx = anyLiabIdx;
                    }

                    String first = kept.get(withoutIdx);
                    String second = kept.get(secondIdx);
                    if (first != null && second != null) {
                        String merged = first.trim() + " " + second.trim();
                        kept.set(withoutIdx, merged);
                        if (secondIdx > withoutIdx) {
                            kept.remove(secondIdx);
                        } else {
                            kept.remove(secondIdx);
                            // index shifted after removal; withoutIdx-1 is the merged element
                            withoutIdx = withoutIdx - 1;
                        }
                    }
                }
            }

            String terms = kept.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                    .trim();
            if (!terms.isBlank()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("salesSampleTerms", terms);
                out.add(row);
            }
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleTimeOfDeliveryByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;

            boolean isSalesSamplePage = false;
            for (OcrNewLine l : pageLines) {
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if ((low.contains("purchase order") && low.contains("sales sample"))
                        || low.contains("sales sample order")
                        || low.equals("sales sample terms")
                        || low.startsWith("sales sample terms")) {
                    isSalesSamplePage = true;
                    break;
                }
            }
            if (!isSalesSamplePage) continue;

            pageLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            int headerIdx = -1;
            for (int i = 0; i < pageLines.size(); i++) {
                String s = oneLine(pageLines.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                // On Sales Sample page, the field label is "Time Of Delivery".
                // Avoid matching the Purchase Order table header by restricting to sales-sample pages above.
                // Also avoid matching the cancellation sentence "... at the Time of Delivery ..." by requiring the line to start with the label.
                if (low.equals("time of delivery") || low.startsWith("time of delivery")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx < 0) continue;

            StringBuilder sb = new StringBuilder();
            for (int i = headerIdx + 1; i < Math.min(pageLines.size(), headerIdx + 30); i++) {
                String s = oneLine(pageLines.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.startsWith("article no")) break;
                if (low.startsWith("by accepting")) break;
                if (low.startsWith("created:")) break;
                if (sb.length() > 0) sb.append(' ');
                sb.append(s);
            }

            String tod = sb.toString().trim();
            if (!tod.isBlank()) {
                tod = tod.replaceAll("(?i)\\bas\\s+soon\\s+possible\\b", "As soon as possible");
            }
            if (tod.isBlank()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("page", String.valueOf(page));
            row.put("timeOfDelivery", tod);
            out.add(row);
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleArticlesByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Pattern datePat = Pattern.compile("\\b\\d{1,2}\\s+[A-Za-z]{3},\\s*\\d{4}\\b");

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;

            List<OcrNewLine> sorted = new ArrayList<>(pageLines);
            sorted.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            boolean isSalesSamplePage = false;
            for (OcrNewLine l : sorted) {
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if ((low.contains("purchase order") && low.contains("sales sample"))
                        || low.contains("sales sample order")
                        || low.equals("sales sample terms")
                        || low.startsWith("sales sample terms")) {
                    isSalesSamplePage = true;
                    break;
                }
            }
            if (!isSalesSamplePage) continue;

            int headerIdx = -1;
            for (int i = 0; i < sorted.size(); i++) {
                String s = oneLine(sorted.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                // Header OCR is often split; keep this check loose and rely on row pattern matching below.
                if (low.startsWith("article no") && low.contains("tod") && (low.contains("qty") || low.contains("quantity"))) {
                    headerIdx = i;
                    break;
                }
            }

            int startIdx = headerIdx >= 0 ? (headerIdx + 1) : 0;

            boolean extractedAny = false;

            for (int i = startIdx; i < sorted.size(); i++) {
                String s = oneLine(sorted.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.startsWith("by accepting") || low.startsWith("created:") || low.startsWith("page:")) {
                    if (extractedAny) break;
                    continue;
                }

                if (!Character.isDigit(s.charAt(0))) continue;
                Matcher dm = datePat.matcher(s);
                if (!dm.find()) continue;
                String date = dm.group();

                String pre = s.substring(0, dm.start()).trim();
                String[] toks = pre.split("\\s+");
                if (toks.length < 6) continue;

                String articleNo = toks[0];
                String hmColourCode = toks[1];
                String ptArticleNumber = toks[2];

                String qty = toks[toks.length - 1];
                if (!qty.matches("\\d+")) continue;
                String sizeRaw = toks[toks.length - 2];
                String size = sizeRaw.replaceAll("[^A-Za-z0-9]", "").trim();

                StringBuilder colourSb = new StringBuilder();
                for (int t = 3; t < toks.length - 2; t++) {
                    String tok = toks[t];
                    if (tok == null || tok.isBlank()) continue;
                    if (colourSb.length() > 0) colourSb.append(' ');
                    colourSb.append(tok);
                }
                String colour = colourSb.toString().trim();

                String destinationStudio = "";
                int look = i + 1;
                if (look < sorted.size()) {
                    String next = oneLine(sorted.get(look).getText()).trim();
                    String nextLow = next.toLowerCase(Locale.ROOT);
                    if (!next.isBlank()
                            && !nextLow.startsWith("by accepting")
                            && !nextLow.startsWith("created:")
                            && !nextLow.startsWith("page:")
                            && next.length() <= 24
                            && next.matches("[A-Za-z]+")) {
                        destinationStudio = next;
                        i = look;
                    }
                }

                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("articleNo", articleNo);
                row.put("hmColourCode", hmColourCode);
                row.put("ptArticleNumber", ptArticleNumber);
                row.put("colour", colour);
                row.put("size", size);
                row.put("qty", qty);
                row.put("tod", date);
                row.put("destinationStudio", destinationStudio);
                out.add(row);
                extractedAny = true;
            }
        }

        return out;
    }

    private static void upsertPurchaseOrderTermsOfDeliveryPage1(List<Map<String, String>> poTermsOfDeliveryByPage, String terms) {
        if (poTermsOfDeliveryByPage == null) return;
        if (terms == null || terms.isBlank()) return;

        Map<String, String> row = new LinkedHashMap<>();
        row.put("page", "1");
        row.put("termsOfDelivery", terms.trim());

        boolean replaced = false;
        for (int i = 0; i < poTermsOfDeliveryByPage.size(); i++) {
            Map<String, String> r = poTermsOfDeliveryByPage.get(i);
            if (r == null) continue;
            String p = (r.get("page") == null ? "" : r.get("page")).trim();
            if (p.equals("1")) {
                poTermsOfDeliveryByPage.set(i, row);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            poTermsOfDeliveryByPage.add(0, row);
        }
    }

    private static void normalizePurchaseOrderTermsOfDeliveryTextFromGlobal(
            List<Map<String, String>> poTermsOfDeliveryByPage,
            String globalTerms
    ) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;
        if (globalTerms == null || globalTerms.isBlank()) return;

        String globalBody = cleanPurchaseOrderTermsOfDeliveryText(stripLeadingCountryLine(globalTerms.trim()));
        if (globalBody.isBlank()) return;

        Pattern countryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");

        for (Map<String, String> row : poTermsOfDeliveryByPage) {
            if (row == null) continue;
            String page = row.get("page");
            if (page != null && page.trim().equals("1")) continue;

            String existing = row.get("termsOfDelivery");
            if (existing == null || existing.isBlank()) continue;

            String normalizedExisting = existing.trim();
            String[] parts = normalizedExisting.split("\\R", 2);
            String firstLine = parts.length > 0 ? parts[0].trim() : "";
            String body = parts.length > 1 ? parts[1].trim() : "";

            boolean bodyLooksValid = false;
            String low = normalizedExisting.toLowerCase(Locale.ROOT);
            if (low.contains("transport by") || low.contains("incoterms") || low.contains("ship by")) {
                bodyLooksValid = true;
            }
            if (bodyLooksValid) continue;

            String countryPrefix = "";
            if (countryLinePat.matcher(firstLine).matches()) {
                countryPrefix = firstLine;
            }

            if (!countryPrefix.isBlank()) {
                row.put("termsOfDelivery", countryPrefix + "\n" + globalBody);
            } else {
                row.put("termsOfDelivery", globalBody);
            }
        }

        // Always clean trailing noise even when the body looks valid (e.g. article code lines)
        for (Map<String, String> row : poTermsOfDeliveryByPage) {
            if (row == null) continue;
            String existing = row.get("termsOfDelivery");
            if (existing == null || existing.isBlank()) continue;

            String normalizedExisting = existing.trim();
            String[] parts = normalizedExisting.split("\\R", 2);
            String firstLine = parts.length > 0 ? parts[0].trim() : "";
            String body = parts.length > 1 ? parts[1].trim() : "";

            Pattern countryLinePat2 = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
            if (countryLinePat2.matcher(firstLine).matches()) {
                String cleanedBody = cleanPurchaseOrderTermsOfDeliveryText(body);
                if (!cleanedBody.isBlank()) {
                    row.put("termsOfDelivery", firstLine + "\n" + cleanedBody);
                }
            } else {
                String cleaned = cleanPurchaseOrderTermsOfDeliveryText(normalizedExisting);
                if (!cleaned.isBlank()) {
                    row.put("termsOfDelivery", cleaned);
                }
            }
        }
    }

    private static String stripLeadingCountryLine(String terms) {
        if (terms == null) return "";
        String t = terms.trim();
        if (t.isBlank()) return "";
        int nl = t.indexOf('\n');
        if (nl < 0) return t;
        String first = t.substring(0, nl).trim();
        Pattern countryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
        if (countryLinePat.matcher(first).matches()) {
            return t.substring(nl + 1).trim();
        }
        return t;
    }

    private static String cleanPurchaseOrderTermsOfDeliveryText(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isBlank()) return "";

        String[] lines = t.split("\\R+");
        List<String> out = new ArrayList<>();

        int lastRelevantIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = oneLine(lines[i]).trim();
            if (line.isBlank()) continue;
            out.add(line);

            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("transport by")
                    || low.contains("incoterms")
                    || low.contains("ship by")
                    || low.contains("origin delivery information")
                    || low.contains("account number")
                    || low.contains("account no")) {
                lastRelevantIdx = out.size() - 1;
            }
        }

        // If we have a clear end marker like "Ship by ..." or "Origin Delivery Information", trim anything after it.
        if (lastRelevantIdx >= 0 && lastRelevantIdx < out.size() - 1) {
            out = new ArrayList<>(out.subList(0, lastRelevantIdx + 1));
        }

        // If still no marker, drop trailing numeric/article-like lines (common OCR spillover)
        while (!out.isEmpty()) {
            String last = out.get(out.size() - 1);
            String low = last.toLowerCase(Locale.ROOT);

            boolean looksLikeDelivery = low.contains("transport by") || low.contains("incoterms") || low.contains("ship by") || low.contains("origin delivery information");
            boolean looksLikeArticle = last.matches("^\\d{2,}.*") || low.contains("usd") || low.contains("gpoo");

            if (!looksLikeDelivery && looksLikeArticle) {
                out.remove(out.size() - 1);
                continue;
            }
            break;
        }

        return String.join("\n", out).trim();
    }

    private static void fillMissingPurchaseOrderInvoiceAvgPriceCountries(
            List<Map<String, String>> poInvoiceAvgPrice,
            List<OcrNewLine> allLines,
            String termsWithCountries
    ) {
        if (poInvoiceAvgPrice == null) return;
        if (allLines == null || allLines.isEmpty()) return;

        String fallbackCountryList = null;
        if (termsWithCountries != null && !termsWithCountries.isBlank()) {
            String firstLine = termsWithCountries.split("\\R", 2)[0].trim();

            // Strict comma-separated list: "SE, DE, ..."
            Pattern countryListPat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)+[A-Z]{2}$");
            if (countryListPat.matcher(firstLine).matches()) {
                fallbackCountryList = firstLine;
            } else {
                // Robust mode: extract 2-letter country codes from the line even if it's
                // mixed with PM codes or parentheses: "SE (PMSCA), US (PM-US)"
                Matcher m = Pattern.compile("\\b([A-Z]{2})\\b").matcher(firstLine);
                LinkedHashSet<String> codes = new LinkedHashSet<>();
                while (m.find()) {
                    String code = m.group(1);
                    if (code != null && !code.isBlank()) codes.add(code.trim());
                }
                if (!codes.isEmpty()) {
                    fallbackCountryList = String.join(", ", codes);
                }
            }
        }
        if (fallbackCountryList == null || fallbackCountryList.isBlank()) return;

        // If we already have a multi-country row, do nothing.
        for (Map<String, String> r : poInvoiceAvgPrice) {
            String c = r == null ? null : r.get("country");
            if (c != null && c.contains(",")) return;
        }

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).toLowerCase(Locale.ROOT).contains("invoice average price")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return;

        Pattern priceOnlyPat = Pattern.compile("^([\\d\\.,]+)\\s+([A-Z]{2,3})$");
        Set<String> detectedPrices = new LinkedHashSet<>();
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 40); i++) {
            String line = texts.get(i);
            if (line == null) continue;
            String s = line.trim();
            if (s.isBlank()) continue;
            String low = s.toLowerCase(Locale.ROOT);
            if (low.contains("sales sample") || low.contains("terms of delivery")
                    || low.contains("time of delivery") || low.contains("quantity per artic")) {
                break;
            }
            if (priceOnlyPat.matcher(s).matches()) {
                detectedPrices.add(s);
            }
        }
        if (detectedPrices.isEmpty()) return;

        Set<String> existingPrices = new HashSet<>();
        for (Map<String, String> r : poInvoiceAvgPrice) {
            String p = r == null ? null : r.get("invoiceAveragePrice");
            if (p != null && !p.isBlank()) existingPrices.add(p.trim());
        }

        for (String price : detectedPrices) {
            if (existingPrices.contains(price)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("invoiceAveragePrice", price);
            row.put("country", fallbackCountryList);
            poInvoiceAvgPrice.add(1, row);
            log.info("[PO-INVOICE-PRICE] Filled missing row from Terms of Delivery: {} | {}", price, fallbackCountryList);
            break;
        }
    }

    private static void fillMissingPurchaseOrderQuantityPerArticleColour(List<Map<String, String>> rows, List<OcrNewLine> allLines) {
        if (rows == null || rows.isEmpty() || allLines == null || allLines.isEmpty()) return;

        int headerIdx = -1;
        for (int i = 0; i < allLines.size(); i++) {
            OcrNewLine l = allLines.get(i);
            if (l == null || l.getText() == null) continue;
            String low = oneLine(l.getText()).toLowerCase(Locale.ROOT);
            if (low.contains("quantity per artic")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return;

        int headerPage = allLines.get(headerIdx).getPage();
        Pattern costQtyPat = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})\\s+([\\d\\s]+)$");
        final int colourColLeftMin = 900;

        StringBuilder colourBuf = new StringBuilder();
        for (int i = headerIdx + 1; i < Math.min(allLines.size(), headerIdx + 40); i++) {
            OcrNewLine l = allLines.get(i);
            if (l == null || l.getText() == null) continue;
            if (l.getPage() != headerPage) break;

            String s = oneLine(l.getText()).trim();
            if (s.isBlank()) continue;
            String low = s.toLowerCase(Locale.ROOT);

            if (low.contains("total quantity") || low.contains("invoice average") || low.contains("time of delivery")) break;
            if (low.contains("article no") || low.contains("colour code") || low.contains("qty/article") || low.contains("cost") || low.equals("colour")) continue;
            if (low.contains("total quantity") || low.contains("invoice average") || low.contains("time of delivery")) break;
            if (costQtyPat.matcher(s).find()) continue;

            // Only capture text from the Colour column region (avoids accidentally concatenating other columns).
            if (l.getLeft() < colourColLeftMin) continue;

            if (colourBuf.length() > 0) colourBuf.append(" ");
            colourBuf.append(s);
        }

        String colour = colourBuf.toString().replaceAll("\\s+", " ").trim();
        if (colour.isBlank()) return;

        Pattern optionLikePat = Pattern.compile("^\\s*[0-9A-Z]{3,}\\s*\\(V\\d+\\)\\s*$");
        for (Map<String, String> r : rows) {
            if (r == null) continue;
            String existing = r.get("colour");
            boolean overwrite = false;
            if (existing == null || existing.isBlank() || existing.length() < 6) overwrite = true;
            if (existing != null && optionLikePat.matcher(existing).find()) overwrite = true;
            if (!overwrite && existing != null) {
                String ex = existing.replaceAll("\\s+", " ").trim();
                if (!ex.isBlank() && colour.length() > ex.length()) {
                    // If OCR-derived colour is a longer/complete version of the existing partial value, replace it.
                    if (colour.startsWith(ex) || colour.contains(ex)) overwrite = true;
                }
            }
            if (overwrite) {
                r.put("colour", colour);
            }
        }
    }

    private static void fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromRegionOcr(
            List<Map<String, String>> poTimeOfDelivery,
            List<OcrNewLine> allLines,
            BufferedImage page1Image,
            TesseractOcrEngine ocrEngine,
            String fileName
    ) {
        if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) return;
        if (allLines == null || allLines.isEmpty()) return;
        if (page1Image == null) return;
        if (ocrEngine == null) return;

        List<OcrNewLine> lines = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            if (l.getPage() != 1) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            lines.add(OcrNewLine.builder()
                    .page(l.getPage())
                    .text(t)
                    .left(l.getLeft())
                    .top(l.getTop())
                    .right(l.getRight())
                    .bottom(l.getBottom())
                    .confidence(l.getConfidence())
                    .words(l.getWords())
                    .build());
        }
        if (lines.isEmpty()) return;

        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("time of delivery")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return;

        int planningLeft = 450;
        int qtyLeft = 1800;
        for (int i = headerIdx; i < Math.min(lines.size(), headerIdx + 10); i++) {
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("planning markets")) {
                planningLeft = Math.max(0, lines.get(i).getLeft() - 50);
            }
            if (low.equals("quantity") || low.contains("quantity %")) {
                qtyLeft = Math.max(planningLeft + 200, lines.get(i).getLeft() - 50);
            }
        }

        Pattern qtyPercentPat = Pattern.compile("(\\d[\\d\\s]+)\\s+(\\d+%|<\\d+%)\\s*$");
        Pattern planningTokenPat = Pattern.compile("\\b([A-Z]{2})\\s*\\(\\s*((?:PM|OL)[- ]?[A-Z0-9]{2,})\\s*\\)?");

        int filled = 0;
        for (Map<String, String> row : poTimeOfDelivery) {
            if (row == null) continue;
            String tod = row.get("timeOfDelivery");
            if (tod == null || tod.isBlank()) continue;
            String existing = row.get("planningMarkets");
            if (existing != null && !existing.isBlank()) continue;

            OcrNewLine dateLine = null;
            for (int i = headerIdx + 1; i < Math.min(lines.size(), headerIdx + 200); i++) {
                OcrNewLine cand = lines.get(i);
                if (cand == null) continue;
                if (!tod.trim().equalsIgnoreCase(cand.getText().trim())) continue;
                dateLine = cand;
                break;
            }
            if (dateLine == null) continue;

            int rowTop = dateLine.getTop();
            int rowTopMin = Math.max(0, rowTop - 35);
            int rowBottomMax = rowTop + 140;

            for (int j = headerIdx + 1; j < Math.min(lines.size(), headerIdx + 220); j++) {
                OcrNewLine next = lines.get(j);
                if (next.getTop() <= rowTop) continue;
                String t = next.getText();
                String tLow = t.toLowerCase(Locale.ROOT);
                if (tLow.contains("quantity per artic") || tLow.contains("article no")
                        || tLow.contains("total quantity") || tLow.startsWith("total:")) {
                    break;
                }
                Matcher dm2 = DATE_PATTERN.matcher(t);
                if (dm2.find()) {
                    rowBottomMax = Math.max(rowTop + 20, Math.min(rowTop + 180, next.getTop() - 2));
                    break;
                }
            }

            int left = Math.max(0, planningLeft - 5);
            int right = Math.min(page1Image.getWidth(), qtyLeft - 5);
            int top = Math.max(0, rowTopMin);
            int bottom = Math.min(page1Image.getHeight(), rowBottomMax);
            int width = Math.max(0, right - left);
            int height = Math.max(0, bottom - top);
            if (width <= 5 || height <= 5) continue;

            String whitelist = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789()-,% <";
            String regionText = ocrEngine.extractTextFromRegion(page1Image, new Rectangle(left, top, width, height), 6, whitelist);
            regionText = oneLine(regionText).trim();
            if (regionText.isBlank()) continue;

            List<String> tokens = new ArrayList<>();
            Matcher tokM = planningTokenPat.matcher(regionText);
            while (tokM.find()) {
                String cc = tokM.group(1);
                String code = tokM.group(2);
                if (cc == null || code == null) continue;
                cc = cc.trim().toUpperCase(Locale.ROOT);
                code = code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                tokens.add(cc + " (" + code + ")");
            }

            String planning;
            if (!tokens.isEmpty()) {
                planning = String.join(", ", tokens);
            } else {
                String cleaned = regionText
                        .replaceAll("\\s+", " ")
                        .replaceAll("\\s*,\\s*", ", ")
                        .trim();
                if (cleaned.isBlank()) continue;
                if (DATE_PATTERN.matcher(cleaned).find()) continue;
                if (qtyPercentPat.matcher(cleaned).find()) continue;
                planning = cleaned;
            }

            row.put("planningMarkets", planning);
            filled++;
            log.info("[PO-TIME-DELIVERY][REGION-OCR] file={} row={} filledPlanningMarkets='{}' regionBBox=[{},{}-{},{}]", fileName, tod.trim(), truncate(planning, 200), left, top, right, bottom);
        }

        if (filled > 0) {
            log.info("[PO-TIME-DELIVERY][REGION-OCR] file={} filledPlanningMarketsRows={}", fileName, filled);
        }
    }

    private static void fillMissingPurchaseOrderTermsOfDeliveryCountryCodeFromRegionOcr(
            List<Map<String, String>> poTermsOfDeliveryByPage,
            List<OcrNewLine> allLines,
            List<BufferedImage> pageImages,
            TesseractOcrEngine ocrEngine,
            String fileName,
            boolean effectiveDebug,
            Set<String> allowedCountryCodes
    ) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;
        if (allLines == null || allLines.isEmpty()) return;
        if (pageImages == null || pageImages.isEmpty()) return;
        if (ocrEngine == null) return;

        Pattern twoLetterCountryPat = Pattern.compile("^[A-Z]{2}$");

        // Find the header bbox for each page to anchor a small crop region under it.
        Map<Integer, List<OcrNewLine>> headerCandidatesByPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.equalsIgnoreCase("Terms of Delivery") || t.toLowerCase(Locale.ROOT).startsWith("terms of delivery")) {
                headerCandidatesByPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
            }
        }
        Map<Integer, OcrNewLine> headerByPage = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<OcrNewLine>> en : headerCandidatesByPage.entrySet()) {
            List<OcrNewLine> cands = en.getValue();
            if (cands == null || cands.isEmpty()) continue;
            // Prefer non-synthetic lines (PDFBox supplement uses left=0,right=1000,empty words).
            OcrNewLine best = null;
            for (OcrNewLine c : cands) {
                if (c == null) continue;
                boolean looksSynthetic = c.getLeft() == 0 && c.getRight() == 1000 && (c.getWords() == null || c.getWords().isEmpty());
                if (looksSynthetic) continue;
                best = c;
                break;
            }
            if (best == null) {
                // Fall back to the first candidate if we have nothing else.
                best = cands.get(0);
            }
            headerByPage.put(en.getKey(), best);
        }
        if (headerByPage.isEmpty()) return;

        // Also capture the first 'Transport by Sea' line per page to bound the crop region.
        Map<Integer, List<OcrNewLine>> transportCandidatesByPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String low = oneLine(l.getText()).toLowerCase(Locale.ROOT);
            if (!low.contains("transport by sea")) continue;
            transportCandidatesByPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }
        Map<Integer, OcrNewLine> transportByPage = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<OcrNewLine>> en : transportCandidatesByPage.entrySet()) {
            List<OcrNewLine> cands = en.getValue();
            if (cands == null || cands.isEmpty()) continue;
            OcrNewLine best = null;
            for (OcrNewLine c : cands) {
                if (c == null) continue;
                boolean looksSynthetic = c.getLeft() == 0 && c.getRight() == 1000 && (c.getWords() == null || c.getWords().isEmpty());
                if (looksSynthetic) continue;
                best = c;
                break;
            }
            if (best == null) best = cands.get(0);
            transportByPage.put(en.getKey(), best);
        }

        int filled = 0;
        for (Map<String, String> row : poTermsOfDeliveryByPage) {
            if (row == null) continue;
            int page;
            try {
                page = Integer.parseInt((row.get("page") == null ? "" : row.get("page")).trim());
            } catch (Exception e) {
                continue;
            }
            if (page <= 0) continue;

            String existing = (row.get("termsOfDelivery") == null ? "" : row.get("termsOfDelivery")).trim();
            if (existing.isBlank()) continue;

            // Skip if it already starts with a 2-letter code line.
            String firstLine = existing;
            int nl = existing.indexOf('\n');
            if (nl >= 0) firstLine = existing.substring(0, nl).trim();
            if (twoLetterCountryPat.matcher(firstLine).matches()) continue;

            String inferred = inferTwoLetterCountryFromPageText(allLines, page);
            if (twoLetterCountryPat.matcher(inferred).matches()
                    && (allowedCountryCodes == null || allowedCountryCodes.isEmpty() || allowedCountryCodes.contains(inferred))) {
                row.put("termsOfDelivery", inferred + "\n" + existing);
                filled++;
                log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} filledCountry='{}' via=inferFromPageText", fileName, page, inferred);
                continue;
            }

            OcrNewLine header = headerByPage.get(page);
            if (header == null) {
                if (effectiveDebug) {
                    log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} skip: header bbox not found", fileName, page);
                }
                continue;
            }
            int pageIndex = page - 1;
            if (pageIndex < 0 || pageIndex >= pageImages.size()) continue;
            BufferedImage img = pageImages.get(pageIndex);
            if (img == null) continue;

            OcrNewLine transport = transportByPage.get(page);

            boolean headerLooksSynthetic = header.getLeft() == 0 && header.getRight() == 1000 && (header.getWords() == null || header.getWords().isEmpty());
            boolean transportLooksSynthetic = transport != null && transport.getLeft() == 0 && transport.getRight() == 1000 && (transport.getWords() == null || transport.getWords().isEmpty());

            int top;
            int bottom;
            int left;
            int right;
            boolean usedTemplateFallback = false;

            if (headerLooksSynthetic || transportLooksSynthetic) {
                OcrNewLine anchor = null;
                for (OcrNewLine l : allLines) {
                    if (l == null || l.getText() == null) continue;
                    if (l.getPage() != page) continue;
                    boolean looksSynthetic = l.getLeft() == 0 && l.getRight() == 1000;
                    if (looksSynthetic) continue;
                    String low = oneLine(l.getText()).toLowerCase(Locale.ROOT);
                    if (low.contains("transport by sea")) {
                        anchor = l;
                        break;
                    }
                    if (anchor == null && (low.startsWith("transport by") || low.contains("transport by"))) {
                        anchor = l;
                    }
                }

                if (anchor == null) {
                    if (effectiveDebug) {
                        log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} fallback: synthetic header/transport bbox and no real content anchor found; using template region", fileName, page);
                    }

                    usedTemplateFallback = true;
                    top = 0;
                    bottom = 0;
                    left = 0;
                    right = 0;
                } else {
                    top = Math.max(0, anchor.getTop() - 40);
                    bottom = Math.min(img.getHeight(), anchor.getTop() + 15);
                    left = Math.max(0, anchor.getLeft() - 60);
                    right = Math.min(img.getWidth(), left + 120);
                }
            } else {
                top = Math.max(0, header.getBottom() + 2);
                bottom = Math.min(img.getHeight(), top + 120);
                if (transport != null) {
                    int transportTop = Math.max(0, transport.getTop());
                    if (transportTop > top + 6) {
                        bottom = Math.min(bottom, transportTop - 2);
                    }
                }

                int anchorLeft = header.getLeft();
                if (transport != null && transport.getLeft() > 0 && transport.getLeft() < img.getWidth()) {
                    anchorLeft = transport.getLeft();
                }
                left = Math.max(0, anchorLeft - 10);
                right = Math.min(img.getWidth(), left + 140);
            }
            int width = Math.max(0, right - left);
            int height = Math.max(0, bottom - top);
            String whitelist = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

            String raw1 = "";
            String raw2 = "";
            String regionText = "";
            int resolvedLeft = left;
            int resolvedTop = top;
            int resolvedRight = right;
            int resolvedBottom = bottom;

            if (usedTemplateFallback) {
                double h = img.getHeight();
                double w = img.getWidth();
                int[][] templates = new int[][]{
                        // Crop around the expected JP/KR token under the 'Terms of Delivery' header.
                        // (x, y, width, height)
                        new int[]{(int) Math.round(w * 0.05), (int) Math.round(h * 0.035), 140, 100},
                        new int[]{(int) Math.round(w * 0.05), (int) Math.round(h * 0.040), 160, 110},
                        new int[]{(int) Math.round(w * 0.05), (int) Math.round(h * 0.045), 180, 130},
                        new int[]{(int) Math.round(w * 0.06), (int) Math.round(h * 0.035), 140, 100},
                        new int[]{(int) Math.round(w * 0.06), (int) Math.round(h * 0.040), 160, 110},
                        new int[]{(int) Math.round(w * 0.06), (int) Math.round(h * 0.045), 180, 130},
                        new int[]{(int) Math.round(w * 0.07), (int) Math.round(h * 0.040), 180, 130}
                };

                for (int i = 0; i < templates.length; i++) {
                    int tLeft = Math.max(0, templates[i][0]);
                    int tTop = Math.max(0, templates[i][1]);
                    int tW = Math.max(0, Math.min(img.getWidth() - tLeft, templates[i][2]));
                    int tH = Math.max(0, Math.min(img.getHeight() - tTop, templates[i][3]));
                    if (tW <= 5 || tH <= 5) continue;

                    BufferedImage cropped;
                    try {
                        cropped = img.getSubimage(tLeft, tTop, tW, tH);
                    } catch (Exception e) {
                        continue;
                    }
                    BufferedImage scaled = scaleUp(cropped, 3);
                    if (scaled == null) continue;
                    Rectangle scaledRect = new Rectangle(0, 0, scaled.getWidth(), scaled.getHeight());

                    String tRaw1 = ocrEngine.extractTextFromRegion(scaled, scaledRect, 8, whitelist);
                    String tRaw2 = "";
                    String tRegion = oneLine(tRaw1)
                            .replaceAll("[^A-Za-z]", "")
                            .trim()
                            .toUpperCase(Locale.ROOT);
                    if (tRegion.length() < 2) {
                        tRaw2 = ocrEngine.extractTextFromRegion(scaled, scaledRect, 7, whitelist);
                        tRegion = oneLine(tRaw2)
                                .replaceAll("[^A-Za-z]", "")
                                .trim()
                                .toUpperCase(Locale.ROOT);
                    }
                    if (tRegion.length() >= 2) tRegion = tRegion.substring(0, 2);

                    raw1 = tRaw1;
                    raw2 = tRaw2;
                    regionText = tRegion;
                    resolvedLeft = tLeft;
                    resolvedTop = tTop;
                    resolvedRight = tLeft + tW;
                    resolvedBottom = tTop + tH;

                    if (twoLetterCountryPat.matcher(regionText).matches()) {
                        break;
                    }
                }
            } else {
                if (width <= 5 || height <= 5) continue;
                raw1 = ocrEngine.extractTextFromRegion(img, new Rectangle(left, top, width, height), 8, whitelist);
                regionText = oneLine(raw1)
                        .replaceAll("[^A-Za-z]", "")
                        .trim()
                        .toUpperCase(Locale.ROOT);

                if (regionText.length() < 2) {
                    // Fallback: single text line
                    raw2 = ocrEngine.extractTextFromRegion(img, new Rectangle(left, top, width, height), 7, whitelist);
                    regionText = oneLine(raw2)
                            .replaceAll("[^A-Za-z]", "")
                            .trim()
                            .toUpperCase(Locale.ROOT);
                }

                if (regionText.length() >= 2) {
                    regionText = regionText.substring(0, 2);
                }
            }
            if (!twoLetterCountryPat.matcher(regionText).matches()) {
                if (effectiveDebug) {
                    log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} noMatch regionText='{}' rawPsm8='{}' rawPsm7='{}' headerBBox=[{},{}-{},{}] transportBBox=[{},{}-{},{}] regionBBox=[{},{}-{},{}]",
                            fileName,
                            page,
                            truncate(regionText, 40),
                            truncate(oneLine(raw1), 60),
                            truncate(oneLine(raw2), 60),
                            header.getLeft(), header.getTop(), header.getRight(), header.getBottom(),
                            transport == null ? -1 : transport.getLeft(),
                            transport == null ? -1 : transport.getTop(),
                            transport == null ? -1 : transport.getRight(),
                            transport == null ? -1 : transport.getBottom(),
                            resolvedLeft, resolvedTop, resolvedRight, resolvedBottom);
                }
                continue;
            }

            if (allowedCountryCodes != null && !allowedCountryCodes.isEmpty() && !allowedCountryCodes.contains(regionText)) {
                if (effectiveDebug) {
                    log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} rejectedCountry='{}' reason=notInAllowedSet", fileName, page, regionText);
                }
                continue;
            }

            row.put("termsOfDelivery", regionText + "\n" + existing);
            filled++;
            log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} page={} filledCountry='{}' regionBBox=[{},{}-{},{}]", fileName, page, regionText, resolvedLeft, resolvedTop, resolvedRight, resolvedBottom);
        }

        if (filled > 0) {
            log.info("[PO-TERMS-DELIVERY][REGION-OCR] file={} filledCountryRows={}", fileName, filled);
        }
    }

    private static String inferTwoLetterCountryFromPageText(List<OcrNewLine> allLines, int page) {
        if (allLines == null || allLines.isEmpty()) return "";
        if (page <= 0) return "";

        List<String> pageTexts = new ArrayList<>();
        boolean hasJapan = false;
        boolean hasKorea = false;
        boolean hasPhilippines = false;
        boolean hasKawasaki = false;
        boolean hasTokyo = false;
        boolean hasSeoul = false;
        boolean hasMakati = false;
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            if (l.getPage() != page) continue;
            String text = oneLine(l.getText());
            pageTexts.add(text);

            String low = text.toLowerCase(Locale.ROOT);
            if (low.contains("japan")) hasJapan = true;
            if (low.contains("korea")) hasKorea = true;
            if (low.contains("philippines")) hasPhilippines = true;
            if (low.contains("kawasaki")) hasKawasaki = true;
            if (low.contains("tokyo")) hasTokyo = true;
            if (low.contains("seoul")) hasSeoul = true;
            if (low.contains("makati")) hasMakati = true;
        }

        int termsHeaderIdx = -1;
        for (int i = 0; i < pageTexts.size(); i++) {
            String low = pageTexts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("terms of delivery")) {
                termsHeaderIdx = i;
                break;
            }
        }

        if (termsHeaderIdx >= 0) {
            int end = Math.min(pageTexts.size(), termsHeaderIdx + 12);
            for (int i = termsHeaderIdx + 1; i < end; i++) {
                String trimmed = pageTexts.get(i).trim();
                if (trimmed.length() == 2) {
                    String code = trimmed.toUpperCase(Locale.ROOT);
                    if (PO_DELIVERY_COUNTRY_CODES.contains(code)) {
                        return code;
                    }
                }
            }
        }

        for (String t : pageTexts) {
            String trimmed = t.trim();
            if (trimmed.length() == 2) {
                String code = trimmed.toUpperCase(Locale.ROOT);
                if (PO_DELIVERY_COUNTRY_CODES.contains(code)) {
                    return code;
                }
            }
        }

        if (hasJapan || hasKawasaki || hasTokyo) return "JP";
        if (hasKorea || hasSeoul) return "KR";
        if (hasPhilippines || hasMakati) return "PH";
        return "";
    }

    private static Set<String> extractTwoLetterCountryCodesFromTermsFirstLine(String terms) {
        if (terms == null || terms.isBlank()) return Set.of();
        String t = terms.trim();
        String firstLine = t.split("\\R", 2)[0].trim();
        if (firstLine.isBlank()) return Set.of();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b([A-Z]{2})\\b").matcher(firstLine);
        while (m.find()) {
            String code = m.group(1);
            if (code != null && !code.isBlank() && PO_DELIVERY_COUNTRY_CODES.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    private static BufferedImage scaleUp(BufferedImage src, int scale) {
        if (src == null) return null;
        if (scale <= 1) return src;
        int w = Math.max(1, src.getWidth() * scale);
        int h = Math.max(1, src.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Pattern that matches a clothing size column label. Accepts:
     *   XS, S, M, L, XL, XXS, XXL    with optional trailing punctuation that OCR
     *                                 may produce in place of '*' (e.g. '*', '+', '.', "'", '"').
     *   Same with a "/P" or "IP" suffix (Tesseract often misreads '/' as 'I').
     * Examples that match: "XS", "S*", "M/P", "XL/P*", "XS+", "XLIP", "M.", "S/P."
     */
    private static final Pattern SIZE_LABEL_PAT = Pattern.compile(
            "(?i)^(?:XX?S|S|M|L|XX?L)(?:[*+.,'\"\u2022])?(?:[/I]P(?:[*+.,'\"\u2022])?)?$");

    /**
     * Collapse spaces inside numeric thousand groups so "1 589" becomes "1589"
     * and "5 730" becomes "5730".
     *
     * Only collapses when the leading part is exactly ONE digit (1-9).
     * Two-digit leading parts like "94 188" are intentionally NOT collapsed
     * because in H&M CSB tables they represent two separate size values
     * (94 and 188), not a single thousand-separated number.
     *
     * Uses a non-digit lookbehind so we only match when the LEADING digit
     * is at the start of a number, never the trailing digit of a longer one.
     *
     * Trade-off: a value like "36 795" (36,795) is NOT collapsed.
     * For H&M docs that's acceptable since individual CSB size values
     * rarely exceed 9,999 and totals in the single-digit thousands
     * (e.g. "6 307") are handled correctly.
     */
    private static String collapseThousandSpaces(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (int i = 0; i < 3; i++) {
            String next = out.replaceAll("(?<!\\d)(\\d)\\s+(\\d{3})(?!\\d)", "$1$2");
            if (next.equals(out)) break;
            out = next;
        }
        return out;
    }

    /**
     * Lenient header detector for "Colour / Size breakdown" — matches both
     * "Colour / Size breakdown" and "Size / Colour breakdown" (reversed order
     * seen in SizePerColourBreakdown PDFs), with/without spaces around the
     * slash and with British/American spellings.
     */
    private static int findColourSizeHeaderIndex(List<String> texts) {
        if (texts == null) return -1;
        for (int i = 0; i < texts.size(); i++) {
            String s = oneLine(texts.get(i)).toLowerCase(Locale.ROOT);
            if (!s.contains("breakdown")) continue;
            if (!s.contains("size")) continue;
            if (s.contains("colour") || s.contains("color")) return i;
        }
        return -1;
    }

    /** Pattern for a bare size base ("XS", "S", "M", "L", "XL", "XXS", "XXL"). */
    private static final Pattern SIZE_BASE_PAT = Pattern.compile("(?i)^(?:XX?S|S|M|L|XX?L)$");

    /** Pattern for a "/P" suffix piece, possibly with trailing punctuation. */
    private static final Pattern SLASH_P_SUFFIX_PAT = Pattern.compile("(?i)^[/I]P[*+.,'\"\u2022]?$");

    /** Pattern for a single trailing punctuation token used in place of "*". */
    private static final Pattern PUNCT_SUFFIX_PAT = Pattern.compile("^[*+.,'\"\u2022]$");

    /**
     * Merge tokens that were split mid-size-label by OCR/PDF column wrapping.
     * Examples (read each as quoted strings; backticks used to avoid an
     * inadvertent javadoc terminator):
     *   `M`    + `/P*`  -> `M/P*`
     *   `S`    + `*`    -> `S*`
     *   `S*`   + `/P`   -> `S* /P`  (rare, but seen)
     *   `S/P`  + `*`    -> `S/P*`
     */
    private static List<String> mergeSplitSizeLabelTokens(String[] tokens) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.length) {
            String tok = tokens[i];
            if (tok == null || tok.isEmpty()) { i++; continue; }
            if (i + 1 < tokens.length) {
                String next = tokens[i + 1];
                if (next != null && !next.isEmpty()) {
                    // base + "/P*" → "base/P*"
                    if (SIZE_BASE_PAT.matcher(tok).matches() && SLASH_P_SUFFIX_PAT.matcher(next).matches()) {
                        out.add(tok + next);
                        i += 2;
                        continue;
                    }
                    // base or "base/P" + bare punctuation → append
                    if (PUNCT_SUFFIX_PAT.matcher(next).matches()
                            && (SIZE_BASE_PAT.matcher(tok).matches()
                                || tok.matches("(?i)^(?:XX?S|S|M|L|XX?L)/P$"))) {
                        out.add(tok + next);
                        i += 2;
                        continue;
                    }
                }
            }
            out.add(tok);
            i++;
        }
        return out;
    }

    /** Extract every size-label-looking token from a line, after merging split labels. */
    private static List<String> extractSizeKeysFromLine(String line) {
        List<String> keys = new ArrayList<>();
        if (line == null || line.isEmpty()) return keys;
        List<String> tokens = mergeSplitSizeLabelTokens(line.split("\\s+"));
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            if (SIZE_LABEL_PAT.matcher(tok).matches()) {
                keys.add(tok.toUpperCase(Locale.ROOT));
            }
        }
        return keys;
    }

    /**
     * Try to parse a single article-data row given a line and the expected number
     * of size columns. Returns the parsed map (with "article", per-size keys, and
     * "total"), or null if the line doesn't have enough integers to be a data row.
     *
     * Strategy: collapse thousand-spaces ("5 730" → "5730"), tokenize, then take
     * the LAST N integer tokens as the size values. Everything before them (minus
     * "article" and size-label noise) becomes the article identifier.
     */
    private static Map<String, String> parseColourSizeDataRow(String raw, List<String> sizeKeys) {
        if (raw == null || raw.isEmpty() || sizeKeys == null || sizeKeys.isEmpty()) return null;
        String low = raw.toLowerCase(Locale.ROOT);
        if (low.startsWith("total")) return null;

        String collapsed = collapseThousandSpaces(raw);
        String[] tokens = collapsed.split("\\s+");
        List<Integer> intIndices = new ArrayList<>();
        List<String> intValues = new ArrayList<>();
        for (int t = 0; t < tokens.length; t++) {
            if (tokens[t].matches("\\d+")) {
                intIndices.add(t);
                intValues.add(tokens[t]);
            }
        }
        int n = sizeKeys.size();
        if (intValues.size() < n) return null;

        int firstSizeIdx = intIndices.get(intIndices.size() - n);
        List<String> values = intValues.subList(intValues.size() - n, intValues.size());

        List<String> articleTokens = new ArrayList<>();
        for (int t = 0; t < firstSizeIdx; t++) {
            String tok = tokens[t];
            if (tok.equalsIgnoreCase("article")) continue;
            if (SIZE_LABEL_PAT.matcher(tok).matches()) continue;
            articleTokens.add(tok);
        }
        String article = String.join(" ", articleTokens).trim();
        if (article.isEmpty()) return null;

        Map<String, String> m = new LinkedHashMap<>();
        m.put("article", article);
        long sum = 0;
        for (int s = 0; s < n; s++) {
            String key = sizeKeys.get(s);
            String val = values.get(s);
            m.put(key, val);
            try { sum += Long.parseLong(val); } catch (NumberFormatException ignore) { /* ignore */ }
        }
        m.put("total", String.valueOf(sum));
        return m;
    }

    /**
     * Walk forward from {@code startIdx} (inclusive) up to {@code maxLook} lines
     * trying to parse each line as an article data row. Returns the first parsed
     * row, or null.
     */
    private static Map<String, String> findFirstDataRow(
            List<String> texts, int startIdx, int maxLook, List<String> sizeKeys) {
        int end = Math.min(startIdx + maxLook, texts.size());
        for (int i = startIdx; i < end; i++) {
            Map<String, String> row = parseColourSizeDataRow(oneLine(texts.get(i)), sizeKeys);
            if (row != null) return row;
        }
        return null;
    }

    /**
     * Extract the "Colour / Size breakdown" sub-table that appears at the bottom of
     * H&M TotalCountryBreakdown PDFs. Returns one map per article row with size column
     * labels (e.g. "XS*", "M/P*") as keys, plus an aggregated "total" key.
     *
     * Two-pass strategy:
     *   PASS 1 (header-anchored): find a "Colour / Size breakdown" header, then
     *     the next size-header line, then the data row.
     *   PASS 2 (structural fallback): scan EVERY merged line on every page and
     *     every tolerance for a line with >=3 size labels. Treat that as the
     *     size-header row, then look for the data row on the same line or the
     *     next 8 lines. This survives even when Tesseract garbled the header.
     *
     * Tolerances tried for both passes: 25 → 12 → 6 px.
     */
    static List<Map<String, String>> extractColourSizeBreakdownFromLines(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        log.info("[CSB] starting extraction over {} pages", byPage.size());

        int[] tolerances = {25, 12, 6};

        // --- PASS 1: header-anchored ---
        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            int pageNo = e.getKey();
            List<OcrNewLine> pageLines = e.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;
            pageLines.sort(Comparator.comparingInt(OcrNewLine::getTop).thenComparingInt(OcrNewLine::getLeft));

            for (int tol : tolerances) {
                List<String> texts = mergeLinesByVisualRow(pageLines, tol);
                if (texts.isEmpty()) continue;

                int idxHeader = findColourSizeHeaderIndex(texts);
                if (idxHeader < 0) continue;

                log.info("[CSB][P1] page={} tol={} headerIdx={} headerText='{}'",
                        pageNo, tol, idxHeader, truncate(oneLine(texts.get(idxHeader)), 200));

                int sizeHeaderIdx = -1;
                List<String> sizeKeys = null;
                int scanEnd = Math.min(idxHeader + 10, texts.size());
                for (int i = idxHeader + 1; i < scanEnd; i++) {
                    List<String> keys = extractSizeKeysFromLine(oneLine(texts.get(i)));
                    if (keys.size() >= 3) {
                        sizeKeys = keys;
                        sizeHeaderIdx = i;
                        break;
                    }
                }

                if (sizeHeaderIdx < 0 || sizeKeys == null) {
                    log.info("[CSB][P1] page={} tol={} no size-header row in next {} lines; dumping",
                            pageNo, tol, scanEnd - idxHeader - 1);
                    for (int i = idxHeader + 1; i < Math.min(idxHeader + 7, texts.size()); i++) {
                        log.info("[CSB][P1]   line[{}]: '{}'", i, truncate(oneLine(texts.get(i)), 200));
                    }
                    continue;
                }

                log.info("[CSB][P1] page={} tol={} sizeHeaderIdx={} sizeKeys={} (n={})",
                        pageNo, tol, sizeHeaderIdx, sizeKeys, sizeKeys.size());

                Map<String, String> row = findFirstDataRow(texts, sizeHeaderIdx, 8, sizeKeys);
                if (row != null) {
                    out.add(row);
                    log.info("[CSB][P1] page={} tol={} parsed row {}", pageNo, tol, row);
                    log.info("[CSB] DONE (header-anchored) rowsParsed={} firstRow={}", out.size(), out.get(0));
                    return out;
                } else {
                    log.info("[CSB][P1] page={} tol={} sizeKeys found but no data row in next 8 lines",
                            pageNo, tol);
                }
            }
        }

        // --- PASS 2: structural fallback (no header text required) ---
        log.info("[CSB][P2] header-anchored failed — trying structural fallback");
        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            int pageNo = e.getKey();
            List<OcrNewLine> pageLines = e.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;
            pageLines.sort(Comparator.comparingInt(OcrNewLine::getTop).thenComparingInt(OcrNewLine::getLeft));

            for (int tol : tolerances) {
                List<String> texts = mergeLinesByVisualRow(pageLines, tol);
                if (texts.isEmpty()) continue;

                for (int i = 0; i < texts.size(); i++) {
                    String line = oneLine(texts.get(i));
                    if (line.isEmpty()) continue;
                    List<String> keys = extractSizeKeysFromLine(line);
                    if (keys.size() < 3) continue;

                    log.info("[CSB][P2] page={} tol={} candidate sizeHeader idx={} keys={} text='{}'",
                            pageNo, tol, i, keys, truncate(line, 200));

                    Map<String, String> row = findFirstDataRow(texts, i, 8, keys);
                    if (row != null) {
                        out.add(row);
                        log.info("[CSB][P2] page={} tol={} parsed row {}", pageNo, tol, row);
                        log.info("[CSB] DONE (structural) rowsParsed={} firstRow={}", out.size(), out.get(0));
                        return out;
                    }
                }
            }
        }

        // --- Final diagnostic dump: print any line on any page that looks
        //     remotely related so we can see what Tesseract actually produced.
        log.info("[CSB] no rows extracted — dumping suspicious lines for diagnosis");
        Pattern suspicious = Pattern.compile("(?i)\\b(size|article|breakdown|colour|color|XS|XL|XXL|XXS|/P|IP)\\b");
        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            int pageNo = e.getKey();
            List<OcrNewLine> pageLines = e.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;
            List<String> texts = mergeLinesByVisualRow(pageLines, 25);
            int dumped = 0;
            for (int i = 0; i < texts.size() && dumped < 25; i++) {
                String line = oneLine(texts.get(i));
                if (line.isEmpty()) continue;
                if (suspicious.matcher(line).find()) {
                    log.info("[CSB][DUMP] page={} idx={} '{}'", pageNo, i, truncate(line, 240));
                    dumped++;
                }
            }
        }
        return out;
    }

    /**
     * Parse a Colour / Size breakdown row out of a free-form text block
     * (typically produced by {@link PDFTextStripper}). This bypasses OCR
     * entirely: when the source PDF carries a real text layer, this is much
     * more reliable than the Tesseract pass which can omit dense table rows.
     *
     * Strategy:
     *   1. Locate the "Colour / Size breakdown" section header (lenient match).
     *   2. Take a 2000-char window after the header so we don't bleed into
     *      the size-label legend that follows.
     *   3. Flatten newlines into spaces, collapse thousand-spaces, tokenize
     *      with size-label split-merge.
     *   4. Find the "Article" token. The size-label tokens that follow form
     *      the size header. The first non-size-label token after them starts
     *      the article identifier; the next N integers are its size values.
     */
    static Map<String, String> extractColourSizeBreakdownFromText(String pageText) {
        if (pageText == null || pageText.isEmpty()) return null;

        String lower = pageText.toLowerCase(Locale.ROOT);
        int hdrIdx = lower.indexOf("colour / size breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("color / size breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("colour/size breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("color/size breakdown");
        // Also try reversed order: "Size / Colour breakdown"
        if (hdrIdx < 0) hdrIdx = lower.indexOf("size / colour breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("size / color breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("size/colour breakdown");
        if (hdrIdx < 0) hdrIdx = lower.indexOf("size/color breakdown");
        if (hdrIdx < 0) {
            // Fallback: find "size breakdown" or "colour breakdown" near each other
            int sb = lower.indexOf("size breakdown");
            if (sb >= 0) {
                int look = Math.max(0, sb - 60);
                String near = lower.substring(look, sb);
                if (near.contains("colour") || near.contains("color")) hdrIdx = sb;
            }
            if (hdrIdx < 0) {
                int cb = lower.indexOf("colour breakdown");
                if (cb < 0) cb = lower.indexOf("color breakdown");
                if (cb >= 0) {
                    int look = Math.max(0, cb - 60);
                    String near = lower.substring(look, cb);
                    if (near.contains("size")) hdrIdx = cb;
                }
            }
            if (hdrIdx < 0) return null;
        }

        int end = Math.min(pageText.length(), hdrIdx + 2000);
        String window = pageText.substring(hdrIdx, end);

        String flat = window.replaceAll("[\\r\\n]+", " ");
        flat = collapseThousandSpaces(flat);

        String[] rawTokens = flat.split("\\s+");
        List<String> tokens = mergeSplitSizeLabelTokens(rawTokens);

        // Find the "Article" token that is immediately followed by ≥3 size labels.
        // This skips false matches like "Article No:", "Article / Product No:", etc.
        int articleIdx = -1;
        List<String> sizeKeys = null;
        int t = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!"article".equalsIgnoreCase(tokens.get(i))) continue;
            List<String> candidateKeys = new ArrayList<>();
            int j = i + 1;
            while (j < tokens.size() && SIZE_LABEL_PAT.matcher(tokens.get(j)).matches()) {
                candidateKeys.add(tokens.get(j).toUpperCase(Locale.ROOT));
                j++;
            }
            if (candidateKeys.size() >= 3) {
                articleIdx = i;
                sizeKeys = candidateKeys;
                t = j;
                break;
            }
        }
        if (articleIdx < 0 || sizeKeys == null) {
            log.info("[CSB][PDFBOX] no 'Article' token with ≥3 size labels in window; tokens preview: {}",
                    truncate(String.join(" ", tokens.subList(0, Math.min(tokens.size(), 30))), 300));
            return null;
        }
        int n = sizeKeys.size();

        // Collect the data block until we hit a delimiter that ends the article row
        // ("Total:", "Article Total:", or another "Article" header below).
        List<String> blockTokens = new ArrayList<>();
        while (t < tokens.size()) {
            String tok = tokens.get(t);
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.equals("article") || low.startsWith("total")) break;
            blockTokens.add(tok);
            t++;
        }

        // Within the block, take the LAST N integer tokens as size values; the
        // tokens before the first such integer are the article identifier (which
        // can legitimately start with a digit, e.g. "001 22-216").
        List<Integer> intIndices = new ArrayList<>();
        List<String> intValues = new ArrayList<>();
        for (int i = 0; i < blockTokens.size(); i++) {
            if (blockTokens.get(i).matches("\\d+")) {
                intIndices.add(i);
                intValues.add(blockTokens.get(i));
            }
        }
        if (intValues.size() < n) {
            log.info("[CSB][PDFBOX] data block has only {} integers (need {}); block: {}",
                    intValues.size(), n,
                    truncate(String.join(" ", blockTokens), 300));
            return null;
        }

        int firstSizeIdx = intIndices.get(intIndices.size() - n);
        List<String> values = intValues.subList(intValues.size() - n, intValues.size());

        List<String> articleTokens = new ArrayList<>();
        for (int i = 0; i < firstSizeIdx; i++) {
            String tok = blockTokens.get(i);
            if (tok.isEmpty()) continue;
            if (tok.matches("\\W+")) continue;
            if (SIZE_LABEL_PAT.matcher(tok).matches()) continue;
            if ("article".equalsIgnoreCase(tok)) continue;
            articleTokens.add(tok);
        }
        String article = String.join(" ", articleTokens).trim();
        if (article.isEmpty()) {
            log.info("[CSB][PDFBOX] empty article after block parse; block: {}",
                    truncate(String.join(" ", blockTokens), 300));
            return null;
        }

        Map<String, String> m = new LinkedHashMap<>();
        m.put("article", article);
        long sum = 0;
        for (int s = 0; s < n; s++) {
            String key = sizeKeys.get(s);
            String val = values.get(s);
            m.put(key, val);
            try { sum += Long.parseLong(val); } catch (NumberFormatException ignore) { /* ignore */ }
        }
        m.put("total", String.valueOf(sum));
        return m;
    }

    /**
     * Native-text extraction of the Colour / Size breakdown table for PDF
     * uploads. Runs {@link PDFTextStripper} per page (then a full-doc pass as
     * a final fallback) and feeds the text to
     * {@link #extractColourSizeBreakdownFromText(String)}.
     *
     * Returns an empty list if the PDF has no embedded text or the table
     * isn't present; the caller can then fall back to the OCR-based extractor.
     */
    static List<Map<String, String>> extractColourSizeBreakdownFromPdfBytes(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return List.of();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            int pageCount = doc.getNumberOfPages();
            log.info("[CSB][PDFBOX] starting PDF-text extraction over {} pages", pageCount);

            // Try both modes: sorted-by-position usually preserves visual rows,
            // but on some H&M PDFs the raw stream order works better.
            boolean[] sortModes = {true, false};

            for (boolean sortByPos : sortModes) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(sortByPos);

                for (int p = 1; p <= pageCount; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String pageText = stripper.getText(doc);

                    String lower = pageText.toLowerCase(Locale.ROOT);
                    int sbIdx = lower.indexOf("size breakdown");
                    if (sbIdx < 0) sbIdx = lower.indexOf("colour breakdown");
                    if (sbIdx < 0) sbIdx = lower.indexOf("color breakdown");
                    log.info("[CSB][PDFBOX] sortByPos={} page={} textLen={} breakdownAt={}",
                            sortByPos, p, pageText.length(), sbIdx);

                    if (sbIdx >= 0) {
                        int dumpStart = Math.max(0, sbIdx - 30);
                        int dumpEnd = Math.min(pageText.length(), sbIdx + 800);
                        String dump = pageText.substring(dumpStart, dumpEnd)
                                .replaceAll("[\\r\\n]+", " | ");
                        log.info("[CSB][PDFBOX] sortByPos={} page={} window: {}",
                                sortByPos, p, truncate(dump, 800));
                    }

                    Map<String, String> row = extractColourSizeBreakdownFromText(pageText);
                    if (row != null) {
                        log.info("[CSB][PDFBOX] sortByPos={} page={} parsed row {}",
                                sortByPos, p, row);
                        return List.of(row);
                    }
                }

                // Full-doc pass within this sort mode.
                stripper.setStartPage(1);
                stripper.setEndPage(pageCount);
                String allText = stripper.getText(doc);
                Map<String, String> row = extractColourSizeBreakdownFromText(allText);
                if (row != null) {
                    log.info("[CSB][PDFBOX] sortByPos={} full-doc parsed row {}", sortByPos, row);
                    return List.of(row);
                }
            }

            log.info("[CSB][PDFBOX] no Colour / Size breakdown row found in PDF text");
            return List.of();
        } catch (IOException e) {
            log.warn("[CSB][PDFBOX] failed to read PDF text: {}", e.getMessage());
            return List.of();
        }
    }

    private static String normalizeNumberToken(String s) {
        if (s == null) return "";
        // Remove spaces, commas, and dots used as thousand separators
        String digits = s.replaceAll("[\\s,.]", "");
        // Keep only digits
        digits = digits.replaceAll("[^0-9]", "");
        // If the token is actually a doubled value like '18571857', collapse to one half
        if (digits.length() >= 2 && (digits.length() % 2 == 0)) {
            int half = digits.length() / 2;
            String a = digits.substring(0, half);
            String b = digits.substring(half);
            if (a.equals(b)) return a;
        }
        return digits;
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

            if (log.isDebugEnabled()) {
                log.debug("[DEST-COUNTRY] page={} merged-text-lines-count={}", e.getKey(), texts.size());
                for (int ti = 0; ti < texts.size(); ti++) {
                    String line = oneLine(texts.get(ti));
                    log.debug("[DEST-COUNTRY] page={} text[{}]={}", e.getKey(), ti, line);
                }
            }

            int pageOutStart = out.size();

            // Collect candidate lines that might contain the country around the header
            // (a few lines before + lines after until the size section begins).
            List<String> countryCandidates = new ArrayList<>();
            {
                int start = Math.max(0, idxHeader - 6);
                int end = Math.min(texts.size() - 1, idxHeader + 18);
                for (int i = start; i <= end; i++) {
                    String t = oneLine(texts.get(i));
                    if (t.isBlank()) continue;

                    if (i == idxHeader) {
                        int bdIdx = t.toLowerCase(Locale.ROOT).indexOf("breakdown");
                        if (bdIdx >= 0) {
                            String afterBd = t.substring(bdIdx + "breakdown".length()).trim();
                            if (!afterBd.isBlank() && !isDestinationCountryNoiseLine(afterBd)) {
                                countryCandidates.add(afterBd);
                            }
                        }
                    }

                    String lower = t.toLowerCase(Locale.ROOT);
                    if (i > idxHeader && (lower.startsWith("assortment") || lower.startsWith("solid") || lower.startsWith("total") || lower.startsWith("bill of material"))) {
                        break;
                    }
                    if (isDestinationCountryNoiseLine(t)) {
                        continue;
                    }
                    countryCandidates.add(t);
                }
            }

            String destinationCountry = "";
            // Pass 1: try each candidate individually
            for (String cand : countryCandidates) {
                String c = extractDestinationCountryFromText(cand);
                if (c != null) { destinationCountry = c; break; }
            }
            // Pass 2: try concatenating consecutive pairs to stitch lines that OCR
            // split across visual rows (e.g. "Germany/Türkiye TR" + "(PM-TR)").
            if (destinationCountry.isBlank()) {
                for (int i = 0; i < countryCandidates.size() - 1; i++) {
                    String combined = countryCandidates.get(i) + " " + countryCandidates.get(i + 1);
                    String c = extractDestinationCountryFromText(combined);
                    if (c != null) { destinationCountry = c; break; }
                }
            }

            // Fallback: some pages OCR the country line outside the expected local window.
            // Scan the full page text as a safety net, but only if the focused scan failed.
            if (destinationCountry.isBlank()) {
                for (int i = 0; i < texts.size(); i++) {
                    String t = oneLine(texts.get(i));
                    if (isDestinationCountryNoiseLine(t)) continue;
                    String c = extractDestinationCountryFromText(t);
                    if (c != null) {
                        destinationCountry = c;
                        break;
                    }
                }
            }

            if (log.isDebugEnabled()) {
                if (destinationCountry.isBlank()) {
                    log.debug("[DEST-COUNTRY] page={} EMPTY, candidates={}", e.getKey(), countryCandidates);
                } else {
                    log.debug("[DEST-COUNTRY] page={} detected='{}'", e.getKey(), destinationCountry);
                }
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
                // Log all lines to trace processing order
                log.info("[LINE-TRACE] page={} idx={} currentRowType={} line='{}'", 
                        e.getKey(), i, 
                        currentRow[0] == null ? "null" : currentRow[0].get("type"), 
                        t.length() > 100 ? t.substring(0, 100) + "..." : t);
                if (lower.startsWith("bill of material")) break;

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

                // Capture 'No of Asst:' value — must run even when currentRow is null
                // so that the backfill path can attach it to the last emitted Assortment row.
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

                if (currentRow[0] == null) {
                    // Try to backfill size lines that appear after Quantity into the last emitted row
                    String tUp = t.toUpperCase(Locale.ROOT);
                    if (tUp.contains("XS") || tUp.contains("XL") || tUp.matches(".*\\b[SML]\\s*\\(.*")) {
                        log.info("[LINE-BACKFILL] currentRow=null, attempting backfill for: '{}'", t);
                        // Try to parse as size line and backfill into last row on this page
                        String size = null;
                        String v = null;
                        Matcher sm = SIZE_VALUE_LINE.matcher(t);
                        if (sm.matches()) {
                            size = normalizeSizeKey(sm.group(1));
                            v = normalizeNumber(sm.group(2));
                        }
                        if (size == null) {
                            Matcher sp = SIZE_VALUE_LINE_PARENS.matcher(t);
                            if (sp.matches()) {
                                size = normalizeSizeKey(sp.group(1));
                                v = normalizeNumber(sp.group(2));
                            }
                        }
                        if (size == null) {
                            Matcher sf = SIZE_VALUE_LINE_FLEX.matcher(t);
                            if (sf.find()) {
                                size = normalizeSizeKey(sf.group(1));
                                v = normalizeNumber(sf.group(2));
                            }
                        }
                        if (size == null) {
                            // Try OCR fix pattern for misread numbers
                            Matcher so = SIZE_VALUE_LINE_OCR_FIX.matcher(t);
                            if (so.matches()) {
                                size = normalizeSizeKey(so.group(1));
                                v = fixOcrNumber(so.group(2));
                            }
                        }
                        if (size != null && v != null && !v.isBlank() && out.size() > pageOutStart) {
                            // Backfill into the last emitted row for this page
                            Map<String, String> lastRow = out.get(out.size() - 1);
                            String existing = lastRow.get(size);
                            if (existing == null || "0".equals(existing)) {
                                log.info("[LINE-BACKFILL] SUCCESS: size={} value={} into row type={}", size, v, lastRow.get("type"));
                                lastRow.put(size, v);
                                // Recalculate total if needed
                                recalculateTotalIfNeeded(lastRow);
                            }
                        }
                    }
                    continue;
                }

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
                    if (size == null) {
                        Matcher pm = Pattern.compile("\\(\\s*([A-Za-z]{1,2})\\s*(?:\\/\\s*P)?\\s*\\)").matcher(t);
                        if (pm.find()) {
                            String inside = pm.group(1);
                            size = normalizeSizeKey(inside);
                        }
                    }
                    if (size != null && v != null && !v.isBlank()) {
                        log.info("[SIZE-PARSE] SIZE_VALUE_LINE matched: line='{}' -> size={} value={}", t, size, v);
                        currentRow[0].put(size, v);
                        sawAnySize[0] = true;
                    }
                    continue;
                }

                // Some countries use numeric size labels at the start, e.g. "155/80A (XS/P)* 30"
                Matcher sp = SIZE_VALUE_LINE_PARENS.matcher(t);
                if (sp.matches()) {
                    String inside = sp.group(1);
                    String v = normalizeNumber(sp.group(2));
                    String size = normalizeSizeKey(inside);
                    if (size != null && v != null && !v.isBlank()) {
                        log.info("[SIZE-PARSE] SIZE_VALUE_LINE_PARENS matched: line='{}' -> size={} value={}", t, size, v);
                        currentRow[0].put(size, v);
                        sawAnySize[0] = true;
                    }
                } else {
                    // Third fallback: flexible pattern for size lines that don't match strict patterns
                    Matcher sf = SIZE_VALUE_LINE_FLEX.matcher(t);
                    if (sf.find()) {
                        String sizeRaw = sf.group(1);
                        String v = normalizeNumber(sf.group(2));
                        String size = normalizeSizeKey(sizeRaw);
                        if (size != null && v != null && !v.isBlank()) {
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_FLEX matched: line='{}' -> size={} value={}", t, size, v);
                            currentRow[0].put(size, v);
                            sawAnySize[0] = true;
                        }
                    } else {
                        // Fourth fallback: OCR fix pattern for misread numbers (e.g., "A" for "41")
                        Matcher so = SIZE_VALUE_LINE_OCR_FIX.matcher(t);
                        if (so.matches()) {
                            String sizeRaw = so.group(1);
                            String rawVal = so.group(2);
                            String v = fixOcrNumber(rawVal);
                            String size = normalizeSizeKey(sizeRaw);
                            if (size != null && v != null && !v.isBlank()) {
                                log.info("[SIZE-PARSE] SIZE_VALUE_LINE_OCR_FIX matched: line='{}' -> size={} rawVal='{}' fixedVal={}", t, size, rawVal, v);
                                currentRow[0].put(size, v);
                                sawAnySize[0] = true;
                            }
                        } else {
                            // Log lines that might look like size lines but didn't match any pattern
                            String tUp = t.toUpperCase(Locale.ROOT);
                            if (tUp.contains("XS") || tUp.contains("XL") || (tUp.contains("(") && tUp.contains(")"))) {
                                log.info("[SIZE-PARSE] NO MATCH for potential size line: '{}'", t);
                            }
                        }
                    }
                }
            }

            if (currentRow[0] != null && (sawAnySize[0] || currentRow[0].containsKey("total") || currentRow[0].containsKey("type"))) {
                ensureSizeDefaults(currentRow[0]);
                out.add(currentRow[0]);
            }

            // IMPORTANT: reconcile Solid FIRST, so fillMissingAssortmentValues
            // gets correct Solid values when deriving Assortment.
            reconcileSolidWithTotal(out, pageOutStart);
            // Fallback for when both Solid and Total have the same OCR truncation error
            fixTruncatedSizeValues(out, pageOutStart);
            fillMissingAssortmentValues(out, pageOutStart, texts);
        }

        return out;
    }

    /**
     * Reconcile the Solid row for a page using the Total row as a trusted reference.
     *
     * Background: the H&M breakdown PDF groups orders into Assortment packs and
     * Solid items. The Total row aggregates both:
     *   Total[size] = Solid[size] + (Assortment[size] * NoOfAsst)
     * where {@code NoOfAsst} is the number of assortment packs and
     * {@code Assortment[size]} is the per-pack quantity of that size.
     *
     * The parser sometimes under-counts Solid sizes due to OCR errors:
     *   - a size value misread as a single digit (e.g. "41" -> "4"),
     *   - a size line skipped entirely (so the size stays 0).
     *
     * This method fixes those cases by:
     *   1. Skipping when Solid is already internally consistent
     *      (sum of its per-size values equals its Quantity field) — OCR
     *      likely parsed correctly, do not touch.
     *   2. Otherwise trusting Total (only if it is internally consistent) and
     *      recomputing: Solid[size] = Total[size] - Assortment[size] * NoOfAsst.
     *
     * Must run BEFORE {@link #fillMissingAssortmentValues} because that method
     * derives Assortment from {@code Total - Solid}; a wrong Solid corrupts it.
     */
    private static void reconcileSolidWithTotal(List<Map<String, String>> rows, int startIdx) {
        if (rows == null || startIdx >= rows.size()) return;

        Map<String, String> assortment = null, solid = null, total = null;
        for (int i = startIdx; i < rows.size(); i++) {
            String type = rows.get(i).getOrDefault("type", "");
            if ("Assortment".equals(type) && assortment == null) assortment = rows.get(i);
            else if ("Solid".equals(type) && solid == null) solid = rows.get(i);
            else if ("Total".equals(type) && total == null) total = rows.get(i);
        }

        if (solid == null || total == null) return;

        List<String> sizes = List.of("XS", "S", "M", "L", "XL");

        // (1) Skip if Solid is already self-consistent (per-size sum == Quantity).
        // Prevents corrupting correctly-parsed rows (e.g., Sweden, Switzerland).
        int solidTotal;
        try {
            solidTotal = Integer.parseInt(solid.getOrDefault("total", "0").replaceAll("\\s+", ""));
        } catch (NumberFormatException e) {
            return;
        }
        int solidSum = 0;
        for (String sz : sizes) {
            try {
                solidSum += Integer.parseInt(solid.getOrDefault(sz, "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
        }
        if (solidTotal > 0 && solidSum == solidTotal) return;

        // (2) Only trust Total if its per-size values sum equals its "total" field.
        int totalExpected;
        try {
            totalExpected = Integer.parseInt(total.getOrDefault("total", "0").replaceAll("\\s+", ""));
        } catch (NumberFormatException e) {
            return;
        }
        if (totalExpected <= 0) return;

        int totalSum = 0;
        for (String sz : sizes) {
            try {
                totalSum += Integer.parseInt(total.getOrDefault(sz, "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
        }
        if (totalSum != totalExpected) return; // Total row not self-consistent, don't trust it

        // (3) Parse No of Asst (number of assortment packs). Defaults to 0.
        int noOfAsst = 0;
        if (assortment != null) {
            try {
                noOfAsst = Integer.parseInt(assortment.getOrDefault("noOfAsst", "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
        }

        // (4) Reconcile using: Solid[size] = Total[size] - (Assortment[size] * NoOfAsst)
        boolean changed = false;
        int newSolidSum = 0;
        for (String sz : sizes) {
            int totVal = 0, assortVal = 0, solidVal = 0;
            try {
                totVal = Integer.parseInt(total.getOrDefault(sz, "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
            try {
                if (assortment != null) {
                    assortVal = Integer.parseInt(assortment.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                }
            } catch (NumberFormatException ignored) {}
            try {
                solidVal = Integer.parseInt(solid.getOrDefault(sz, "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}

            int assortContribution = assortVal * noOfAsst;
            int expectedSolid = Math.max(0, totVal - assortContribution);
            if (solidVal != expectedSolid) {
                log.info("[SOLID-RECONCILE] size={} old={} new={} (total={} - assortment={}*noOfAsst={}) country={}",
                        sz, solidVal, expectedSolid, totVal, assortVal, noOfAsst,
                        solid.getOrDefault("destinationCountry", ""));
                solid.put(sz, String.valueOf(expectedSolid));
                changed = true;
            }
            newSolidSum += expectedSolid;
        }

        if (changed) {
            solid.put("total", String.valueOf(newSolidSum));
        }
    }

    /**
     * Fallback fix for when BOTH Solid and Total rows have truncated size values.
     *
     * This handles a rare OCR error pattern where a two-digit number like "41" is
     * read as a single digit "4" (or letter "A" → 4) in BOTH Solid and Total rows.
     * Since both are corrupted, {@link #reconcileSolidWithTotal} cannot help.
     *
     * Detection: if Solid and Total are both missing the SAME amount from their
     * respective Quantity fields, and they share a size with an identical small
     * value (single digit), that size likely had its trailing digit(s) truncated.
     *
     * Fix: add the missing amount to that size in both rows.
     *
     * Example (Malaysia page 14):
     *   Solid: XS=85, S=107, M=75, L=60, XL=4, Quantity=368 → sum=331, missing=37
     *   Total: XS=115, S=137, M=105, L=60, XL=4, Quantity=458 → sum=421, missing=37
     *   Both XL=4, both missing 37 → XL should be 4+37=41.
     */
    private static void fixTruncatedSizeValues(List<Map<String, String>> rows, int startIdx) {
        if (rows == null || startIdx >= rows.size()) return;

        Map<String, String> solid = null, total = null;
        for (int i = startIdx; i < rows.size(); i++) {
            String type = rows.get(i).getOrDefault("type", "");
            if ("Solid".equals(type) && solid == null) solid = rows.get(i);
            else if ("Total".equals(type) && total == null) total = rows.get(i);
        }

        if (solid == null || total == null) return;

        List<String> sizes = List.of("XS", "S", "M", "L", "XL");

        // Parse Solid values
        int solidQuantity, solidSum = 0;
        int[] solidVals = new int[sizes.size()];
        try {
            solidQuantity = Integer.parseInt(solid.getOrDefault("total", "0").replaceAll("\\s+", ""));
        } catch (NumberFormatException e) {
            return;
        }
        for (int i = 0; i < sizes.size(); i++) {
            try {
                solidVals[i] = Integer.parseInt(solid.getOrDefault(sizes.get(i), "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
            solidSum += solidVals[i];
        }

        // Parse Total values
        int totalQuantity, totalSum = 0;
        int[] totalVals = new int[sizes.size()];
        try {
            totalQuantity = Integer.parseInt(total.getOrDefault("total", "0").replaceAll("\\s+", ""));
        } catch (NumberFormatException e) {
            return;
        }
        for (int i = 0; i < sizes.size(); i++) {
            try {
                totalVals[i] = Integer.parseInt(total.getOrDefault(sizes.get(i), "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException ignored) {}
            totalSum += totalVals[i];
        }

        // Both must be inconsistent
        if (solidSum == solidQuantity || totalSum == totalQuantity) return;

        int solidMissing = solidQuantity - solidSum;
        int totalMissing = totalQuantity - totalSum;

        // Both must be missing the SAME positive amount
        if (solidMissing <= 0 || solidMissing != totalMissing) return;

        // Find a size where both Solid and Total have the same small value (likely truncated)
        // Prefer single-digit values as they're most likely truncated two-digit numbers
        int candidateIdx = -1;
        for (int i = 0; i < sizes.size(); i++) {
            if (solidVals[i] == totalVals[i] && solidVals[i] > 0 && solidVals[i] < 10) {
                // Check if adding missing would result in a reasonable value
                int corrected = solidVals[i] + solidMissing;
                // Sanity: corrected should be a plausible 2-digit number
                if (corrected >= 10 && corrected < 1000) {
                    candidateIdx = i;
                    break;
                }
            }
        }

        if (candidateIdx < 0) return;

        String sz = sizes.get(candidateIdx);
        int oldVal = solidVals[candidateIdx];
        int newVal = oldVal + solidMissing;

        log.info("[TRUNCATION-FIX] size={} old={} new={} (missing={}) country={}",
                sz, oldVal, newVal, solidMissing, solid.getOrDefault("destinationCountry", ""));

        // Fix both Solid and Total
        solid.put(sz, String.valueOf(newVal));
        total.put(sz, String.valueOf(newVal));

        // Update totals (they should now match Quantity)
        solid.put("total", String.valueOf(solidSum + solidMissing));
        total.put("total", String.valueOf(totalSum + totalMissing));
    }

    private static void fillMissingAssortmentValues(
            List<Map<String, String>> rows, int startIdx, List<String> texts) {
        if (rows == null || startIdx >= rows.size()) return;

        // Try to read 'Quantity:' (Assortment) or 'No of Asst:' if present, but we won't depend on it.
        Integer qtyAssort = null;
        for (String t : texts) {
            String lower = t.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("quantity")) {
                Matcher m = Pattern.compile("(\\d[\\d\\s]*?)\\s*$").matcher(t);
                if (m.find()) {
                    try { qtyAssort = Integer.parseInt(m.group(1).replaceAll("\\s+", "")); }
                    catch (NumberFormatException ignored) {}
                }
                // don't break; later lines may relate to other sections
            }
        }

        // Find Assortment, Solid, Total rows added for this page
        Map<String, String> assortment = null, solid = null, total = null;
        for (int i = startIdx; i < rows.size(); i++) {
            String type = rows.get(i).getOrDefault("type", "");
            if ("Assortment".equals(type) && assortment == null) assortment = rows.get(i);
            else if ("Solid".equals(type) && solid == null) solid = rows.get(i);
            else if ("Total".equals(type) && total == null) total = rows.get(i);
        }

        if (assortment == null || solid == null || total == null) return;

        // Skip only if Assortment already has any positive (>0) size value
        List<String> sizes = List.of("XS", "S", "M", "L", "XL");
        for (String sz : sizes) {
            String v = assortment.get(sz);
            if (v != null && !v.isBlank()) {
                try {
                    int iv = Integer.parseInt(v.replaceAll("\\s+", ""));
                    if (iv > 0) return; // already meaningful, do not override
                } catch (NumberFormatException ignored) { return; }
            }
        }

        // Build diffs and compute GCD across positive diffs
        int gcd = 0;
        int[] diffs = new int[sizes.size()];
        for (int i = 0; i < sizes.size(); i++) {
            String sz = sizes.get(i);
            try {
                int tVal = Integer.parseInt(total.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                int sVal = Integer.parseInt(solid.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                int diff = Math.max(0, tVal - sVal);
                diffs[i] = diff;
                if (diff > 0) gcd = (gcd == 0) ? diff : gcd(gcd, diff);
            } catch (NumberFormatException ignored) {}
        }

        if (gcd <= 0) return;

        // Prefer using GCD; only switch to 'Quantity' if it divides diffs AND
        // the resulting per-pack sizes sum equals the Quantity value.
        int divisor = gcd;
        if (qtyAssort != null && qtyAssort > 0) {
            boolean allDivisible = true;
            for (int d : diffs) { if (d % qtyAssort != 0) { allDivisible = false; break; } }
            if (allDivisible) {
                int sumIfQty = 0;
                for (int d : diffs) sumIfQty += d / qtyAssort;
                if (sumIfQty == qtyAssort) {
                    divisor = qtyAssort;
                } else {
                    divisor = gcd; // keep gcd when more plausible
                }
            }
        }

        int sum = 0;
        for (int i = 0; i < sizes.size(); i++) {
            int val = (divisor > 0) ? (diffs[i] / divisor) : 0;
            if (val < 0) val = 0;
            if (val > 0) sum += val;
            assortment.put(sizes.get(i), String.valueOf(val));
        }
        assortment.put("total", String.valueOf(sum));
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
        
        // NOTE: Previously there was "verification" logic here that tried to correct
        // a size value when sum != total by adding the entire diff to the first size
        // with value < 10. This was removed because it caused incorrect results when
        // multiple sizes had OCR errors (e.g., Malaysia page: XS misread as 4 AND M
        // line missed entirely -> XS incorrectly corrected to 82 instead of 41).
        //
        // Correction is now handled by reconcileSolidWithTotal() which uses the Total
        // row as a trusted reference: Solid[size] = Total[size] - Assortment[size].

        String tot = row.get("total");
        // If total missing or blank: set computed sum if we had any numeric values; otherwise default to 0
        if (tot == null || tot.isBlank()) {
            row.put("total", String.valueOf(anyNumeric ? sum : 0));
        }
    }

    /**
     * Recalculate the total for a row after a size value has been backfilled.
     * Only updates total if the current total seems to be missing the newly added value.
     */
    private static void recalculateTotalIfNeeded(Map<String, String> row) {
        if (row == null) return;
        List<String> sizes = List.of("XS", "S", "M", "L", "XL");
        int sum = 0;
        for (String sz : sizes) {
            String v = row.get(sz);
            if (v != null && !v.isBlank()) {
                try {
                    sum += Integer.parseInt(v.replaceAll("\\s+", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        // Update total to match the sum of sizes
        row.put("total", String.valueOf(sum));
    }

    /**
     * Attempt to fix OCR-misread characters in a number string.
     * Common OCR errors: A->4, l/I->1, O->0, S->5, B->8, etc.
     * If the input is already a valid number, return it normalized.
     * If the input is a single letter that commonly represents a digit, try to convert.
     */
    private static String fixOcrNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        
        // If already a number, normalize and return
        if (s.matches("\\d[\\d\\s]*")) {
            return normalizeNumber(s);
        }
        
        // Try character-by-character replacement for common OCR errors
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case 'O': case 'o': sb.append('0'); break;
                case 'l': case 'I': case 'i': sb.append('1'); break;
                case 'S': case 's': sb.append('5'); break;
                case 'B': sb.append('8'); break;
                case 'A': sb.append('4'); break;  // Common OCR misread
                case 'Z': case 'z': sb.append('2'); break;
                case 'G': case 'g': sb.append('6'); break;
                case 'T': case 't': sb.append('7'); break;
                case ' ': break; // skip spaces
                default:
                    if (Character.isDigit(c)) {
                        sb.append(c);
                    }
                    // Skip other non-digit characters
                    break;
            }
        }
        
        String result = sb.toString();
        if (result.isEmpty()) return null;
        
        // If the result is a valid number, return it
        if (result.matches("\\d+")) {
            return result;
        }
        
        return null;
    }

    /**
     * Try to extract a destination-country string from a (possibly merged) OCR line.
     * Returns the country portion, e.g. "Sweden SE (PMSCA)", or null if not found.
     * Works even when extra text is appended (e.g. "Sweden SE (PMSCA) Article No: 001")
     * or when OCR mangled the 2-letter country code outside the parens.
     */
    private static String extractDestinationCountryFromText(String t) {
        if (t == null) return null;
        String s = oneLine(t).trim();
        if (s.isBlank()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("h&m colour") || lower.startsWith("colour name")) return null;
        Matcher codeMatcher = DEST_COUNTRY_CODE_PAT.matcher(s);
        if (codeMatcher.find()) {
            String prefix = s.substring(0, codeMatcher.start()).trim();
            if (prefix.isEmpty() || !prefix.matches(".*[A-Za-z].*")) return null;
            String code = codeMatcher.group().toUpperCase(Locale.ROOT).replace(' ', '-');
            prefix = prefix.replaceAll("\\s*\\($", "").trim();
            return prefix + " (" + code + ")";
        }
        if (!s.contains("(")) return null;
        Matcher m = DEST_COUNTRY_PAT.matcher(s);
        if (m.find()) {
            String prefix = s.substring(0, m.start()).trim();
            if (prefix.isEmpty() || !prefix.matches(".*[A-Za-z].*")) return null;
            String cleaned = s.substring(0, m.end()).trim();
            if (cleaned.endsWith("(")) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
            return cleaned;
        }
        return null;
    }

    private static boolean isDestinationCountryNoiseLine(String t) {
        if (t == null) return true;
        String s = oneLine(t).trim();
        if (s.isBlank()) return true;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("article no")) return true;
        if (lower.startsWith("article / product no")) return true;
        if (lower.startsWith("h&m colour")) return true;
        if (lower.startsWith("colour name")) return true;
        if (lower.startsWith("description")) return true;
        if (lower.startsWith("pt article number")) return true;
        if (lower.startsWith("pt prod no")) return true;
        if (lower.startsWith("product name")) return true;
        if (lower.startsWith("product description")) return true;
        if (lower.startsWith("supplier name")) return true;
        if (lower.startsWith("development no")) return true;
        if (lower.startsWith("option no")) return true;
        if (s.trim().matches("^\\d{1,6}$")) return true;
        if (s.contains(":")) {
            String up = s.toUpperCase(Locale.ROOT);
            return !(up.contains("(PM") || up.matches(".*\\bPM[- ]?[A-Z]{2,}.*"));
        }
        return false;
    }

    private static String normalizeSizeKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        s = s.replaceAll("\\*+", "");
        if (s.endsWith("/P")) {
            s = s.substring(0, s.length() - 2);
        }
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

    private void logComparisonForPage(int pageNumber, List<OcrNewLine> hocrLines, List<OcrNewLine> rawLines) {
        if (!log.isInfoEnabled()) return;

        int hocrCount = hocrLines == null ? 0 : hocrLines.size();
        int rawCount = rawLines == null ? 0 : rawLines.size();
        log.info("[OCR-NEW][COMPARE] page={} hocrCount={} rawCount={}", pageNumber, hocrCount, rawCount);

        if (hocrLines != null) {
            for (int i = 0; i < hocrLines.size(); i++) {
                OcrNewLine l = hocrLines.get(i);
                log.info("[OCR-NEW][COMPARE] page={} hocr[{}]: bbox=[{},{}-{},{}], conf={}, text={}",
                        pageNumber,
                        i,
                        l.getLeft(), l.getTop(), l.getRight(), l.getBottom(),
                        round1(l.getConfidence()),
                        truncate(oneLine(l.getText()), 240));
            }
        }

        if (rawLines != null) {
            for (int i = 0; i < rawLines.size(); i++) {
                OcrNewLine l = rawLines.get(i);
                log.info("[OCR-NEW][COMPARE] page={} raw[{}]: bbox=[{},{}-{},{}], conf={}, text={}",
                        pageNumber,
                        i,
                        l.getLeft(), l.getTop(), l.getRight(), l.getBottom(),
                        round1(l.getConfidence()),
                        truncate(oneLine(l.getText()), 240));
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        String t = s == null ? "" : s;
        if (t.length() <= maxLen) return t;
        return t.substring(0, Math.max(0, maxLen)) + "...";
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file) {
        return analyzeDocument(file, null, true);  // hOCR default for better fragment handling
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file, Boolean debugOverride) {
        return analyzeDocument(file, debugOverride, true);  // hOCR default for better fragment handling
    }

    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file, Boolean debugOverride, boolean useHocr, boolean compareModes) {
        validateFile(file);

        boolean effectiveDebug = debugLogging || Boolean.TRUE.equals(debugOverride);

        try {
            byte[] fileBytes = file.getBytes();

            if (effectiveDebug) {
                log.info("[OCR-NEW][DEBUG] analyzeDocument start: fileName={}, contentType={}, sizeBytes={}, renderDpi={}, debugOverride={}, useHocr={}, compareModes={}",
                        file.getOriginalFilename(), file.getContentType(), fileBytes.length, renderDpi, debugOverride, useHocr, compareModes);
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
                log.info("[OCR-NEW][DEBUG] rendered pages: pageCount={}, useHocr={}, compareModes={}", pageImages.size(), useHocr, compareModes);
                for (int i = 0; i < Math.min(pageImages.size(), 5); i++) {
                    BufferedImage img = pageImages.get(i);
                    log.info("[OCR-NEW][DEBUG] pageImage[{}]: width={}, height={}", i, img.getWidth(), img.getHeight());
                }
                if (pageImages.size() > 5) {
                    log.info("[OCR-NEW][DEBUG] pageImage: {} more pages not logged", pageImages.size() - 5);
                }
            }

            List<OcrNewLine> allLines = new ArrayList<>();
            // Always keep a raw OCR pass from the rendered PNG pages for sales-order detail extraction.
            List<OcrNewLine> rawLinesForDetail = new ArrayList<>();
            List<OcrNewLine> rawLinesForTables = useHocr ? new ArrayList<>() : allLines;
            List<BufferedImage> cleanedPageImages = new ArrayList<>();
            for (int i = 0; i < pageImages.size(); i++) {
                BufferedImage pageImage = removeColoredBorders(pageImages.get(i));
                cleanedPageImages.add(pageImage);
                List<OcrNewLine> hocrPageLines = null;
                List<OcrNewLine> rawPageLines = ocrEngine.extractLinesFromImage(pageImage, i);

                if (useHocr || compareModes) {
                    hocrPageLines = ocrEngine.extractLinesWithHocr(pageImage, i);
                }

                if (compareModes && effectiveDebug) {
                    logComparisonForPage(i + 1, hocrPageLines, rawPageLines);
                }

                rawLinesForDetail.addAll(rawPageLines == null ? List.of() : rawPageLines);
                if (useHocr) {
                    allLines.addAll(hocrPageLines == null ? List.of() : hocrPageLines);
                    rawLinesForTables.addAll(rawPageLines == null ? List.of() : rawPageLines);
                } else {
                    allLines.addAll(rawPageLines == null ? List.of() : rawPageLines);
                }
            }

            // ── Supplementary: PDFBox native text for lines Tesseract missed ───
            String fname = file.getOriginalFilename();
            if (isPdf(file)) {
                try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(fileBytes))) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    int totalPages = doc.getNumberOfPages();
                    for (int p = 0; p < totalPages; p++) {
                        int pageNum = p + 1; // 1-indexed
                        stripper.setStartPage(pageNum);
                        stripper.setEndPage(pageNum);
                        String pageText = stripper.getText(doc);
                        if (pageText == null || pageText.isBlank()) continue;

                        // Collect existing Tesseract line texts for this page (lowered, trimmed) for dedup
                        Set<String> existingTexts = new HashSet<>();
                        for (OcrNewLine ol : allLines) {
                            if (ol.getPage() == pageNum) {
                                existingTexts.add(oneLine(ol.getText()).toLowerCase(Locale.ROOT).trim());
                            }
                        }

                        String[] nativeLines = pageText.split("\\r?\\n");
                        int syntheticTop = 0;
                        for (String nl : nativeLines) {
                            String trimmed = nl.trim();
                            if (trimmed.isBlank()) continue;
                            String key = trimmed.toLowerCase(Locale.ROOT);
                            // Skip if Tesseract already captured a line containing this text
                            boolean alreadyPresent = false;
                            for (String et : existingTexts) {
                                if (et.contains(key) || key.contains(et)) {
                                    alreadyPresent = true;
                                    break;
                                }
                            }
                            if (alreadyPresent) continue;

                            // Add as supplementary line with synthetic bounding box
                            syntheticTop += 5;
                            allLines.add(OcrNewLine.builder()
                                    .page(pageNum)
                                    .text(trimmed)
                                    .left(0).top(syntheticTop).right(1000).bottom(syntheticTop + 20)
                                    .confidence(99f)
                                    .words(List.of())
                                    .build());
                            log.info("[OCR-PDFBOX-SUPPLEMENT] file={} page={} added: {}", fname, pageNum, truncate(trimmed, 300));
                        }
                    }
                } catch (IOException ex) {
                    log.warn("[OCR-PDFBOX-SUPPLEMENT] failed: {}", ex.getMessage());
                }
            }

            if (!cleanedPageImages.isEmpty()) {
                int pageCount = cleanedPageImages.size();
                for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                    BufferedImage pageImage = cleanedPageImages.get(pageNum - 1);
                    List<OcrNewLine> pageLines = new ArrayList<>();
                    for (OcrNewLine l : allLines) {
                        if (l != null && l.getPage() == pageNum) {
                            pageLines.add(l);
                        }
                    }
                    if (pageLines.isEmpty()) continue;

                    List<OcrNewLine> recovered = recoverSalesSampleCancellationLineFromRegion(pageImage, pageNum, pageLines);
                    if (recovered != null && !recovered.isEmpty()) {
                        allLines.addAll(recovered);
                        if (effectiveDebug) {
                            for (OcrNewLine rl : recovered) {
                                log.info("[OCR-REGION-SUPPLEMENT] page={} added: {}", pageNum, truncate(oneLine(rl.getText()), 300));
                            }
                        }
                    }
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
            enrichDeliveryFields(formFields, allLines);
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

            // Extract using both methods; prefer the raw PNG OCR line pass for sales-order detail
            // because it is more stable than hOCR for the destination country row.
            List<Map<String, String>> tableDetail = extractSalesOrderDetailSizeBreakdown(tables);
            List<Map<String, String>> lineDetail = extractSalesOrderDetailSizeBreakdownFromLines(rawLinesForDetail, formFields);
            List<Map<String, String>> salesOrderDetailSizeBreakdown = !lineDetail.isEmpty() ? lineDetail : tableDetail;

            // Extract Total Country Breakdown from tables (Country/PM/size cols/Total)
            List<Map<String, String>> totalCountryBreakdown = extractTotalCountryBreakdownFromTables(tables);
            if (totalCountryBreakdown == null || totalCountryBreakdown.isEmpty()) {
                // Fallback: try line-based extraction around 'Colour / Country Breakdown' section
                totalCountryBreakdown = extractTotalCountryBreakdownFromLines(allLines);
            }

            // Extract the smaller "Colour / Size breakdown" sub-table that lives at the
            // bottom of TotalCountryBreakdown PDFs (Article + per-size qty).
            //
            // Prefer the PDFBox-based native-text extractor for PDF uploads: Tesseract
            // tends to drop this dense table on H&M docs even when the rest of the page
            // OCRs fine. The OCR/line-based extractor remains a fallback for image
            // uploads or PDFs without an embedded text layer.
            List<Map<String, String>> colourSizeBreakdown = List.of();
            if (isPdf(file)) {
                colourSizeBreakdown = extractColourSizeBreakdownFromPdfBytes(fileBytes);
            }
            if (colourSizeBreakdown == null || colourSizeBreakdown.isEmpty()) {
                colourSizeBreakdown = extractColourSizeBreakdownFromLines(allLines);
            }

            // ── Purchase Order specific extractions ────────────────────────────
            List<Map<String, String>> poTimeOfDelivery = extractPurchaseOrderTimeOfDelivery(allLines);
            if (isPdf(file) && poTimeOfDelivery != null && !poTimeOfDelivery.isEmpty() && pageImages != null && !pageImages.isEmpty()) {
                fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromRegionOcr(poTimeOfDelivery, allLines, pageImages.get(0), ocrEngine, fname);
            }
            if (isPdf(file) && poTimeOfDelivery != null && !poTimeOfDelivery.isEmpty()) {
                fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromPdfBytes(poTimeOfDelivery, fileBytes, fname);
            }
            List<Map<String, String>> poTermsOfDeliveryByPage = extractPurchaseOrderTermsOfDeliveryByPage(allLines);
            Set<String> allowedTermsCountries = extractTwoLetterCountryCodesFromTermsFirstLine(formFields.get("Terms of Delivery"));
            fillMissingPurchaseOrderTermsOfDeliveryCountryCodeFromRegionOcr(poTermsOfDeliveryByPage, allLines, pageImages, ocrEngine, fname, effectiveDebug, allowedTermsCountries);

            List<Map<String, String>> poQuantityPerArticle = extractPurchaseOrderQuantityPerArticleByPage(allLines);
            // Pages > 1 often lose this dense table in hOCR; supplement with PDFBox text extraction when PDF.
            if (isPdf(file)) {
                List<Map<String, String>> poQuantityFromPdf = extractPurchaseOrderQuantityPerArticleFromPdfBytes(fileBytes);
                if (poQuantityFromPdf != null && !poQuantityFromPdf.isEmpty()) {
                    if (poQuantityPerArticle == null) poQuantityPerArticle = new ArrayList<>();
                    Set<String> existingKeys = new HashSet<>();
                    for (Map<String, String> r : poQuantityPerArticle) {
                        if (r == null) continue;
                        String k = (nvl(r.get("page")) + "|" + nvl(r.get("articleNo")) + "|" + nvl(r.get("hmColourCode")) + "|" + nvl(r.get("ptArticleNumber")) + "|" + nvl(r.get("optionNo")) + "|" + nvl(r.get("qtyArticle")));
                        existingKeys.add(k);
                    }
                    for (Map<String, String> r : poQuantityFromPdf) {
                        if (r == null) continue;
                        String k = (nvl(r.get("page")) + "|" + nvl(r.get("articleNo")) + "|" + nvl(r.get("hmColourCode")) + "|" + nvl(r.get("ptArticleNumber")) + "|" + nvl(r.get("optionNo")) + "|" + nvl(r.get("qtyArticle")));
                        if (!existingKeys.contains(k)) {
                            poQuantityPerArticle.add(r);
                            existingKeys.add(k);
                        }
                    }
                }
            }
            if (poQuantityPerArticle == null || poQuantityPerArticle.isEmpty()) {
                poQuantityPerArticle = extractPurchaseOrderQuantityPerArticle(allLines);
                for (Map<String, String> r : poQuantityPerArticle) {
                    if (r != null && !r.containsKey("page")) r.put("page", "1");
                }
            }
            fillMissingPurchaseOrderQuantityPerArticleColour(poQuantityPerArticle, allLines);

            // IMPORTANT: Use the global extractor to keep the proven-valid pairing logic
            // (price-only line + country-only line, multi-country lists, etc.).
            // Then annotate each extracted row with a best-effort page number so the frontend
            // can still filter by active page.
            List<Map<String, String>> poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPriceByPage(allLines);
            if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) {
                poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPrice(allLines);
                annotatePurchaseOrderInvoiceAvgPricePages(poInvoiceAvgPrice, allLines);
            }

            String terms = formFields.get("Terms of Delivery");
            String termsWithCountries = addPurchaseOrderTermsDeliveryCountryCodes(terms, allLines, poTimeOfDelivery);
            if (termsWithCountries != null && !termsWithCountries.isBlank()) {
                formFields.put("Terms of Delivery", termsWithCountries);
            }

            // Keep a consistent per-page Terms of Delivery source in the frontend: page 1 should
            // use the proven-valid global extractor result (formFields) instead of accidentally
            // capturing nearby section/table headers.
            upsertPurchaseOrderTermsOfDeliveryPage1(poTermsOfDeliveryByPage, formFields.get("Terms of Delivery"));

            normalizePurchaseOrderTermsOfDeliveryTextFromGlobal(poTermsOfDeliveryByPage, formFields.get("Terms of Delivery"));

            String termsForInvoiceFallback = termsWithCountries;
            if (termsForInvoiceFallback == null || termsForInvoiceFallback.isBlank()) {
                termsForInvoiceFallback = formFields.get("Terms of Delivery");
            }
            fillMissingPurchaseOrderInvoiceAvgPriceCountries(poInvoiceAvgPrice, allLines, termsForInvoiceFallback);

            List<Map<String, String>> salesSampleTermsByPage = extractSalesSampleTermsByPage(allLines);
            List<Map<String, String>> salesSampleTimeOfDeliveryByPage = extractSalesSampleTimeOfDeliveryByPage(allLines);
            List<Map<String, String>> salesSampleArticlesByPage = extractSalesSampleArticlesByPage(allLines);
            if (effectiveDebug) {
                log.info("[SALES-SAMPLE-TERMS] extracted rows: {}", salesSampleTermsByPage == null ? 0 : salesSampleTermsByPage.size());
                for (int i = 0; i < Math.min(10, salesSampleTermsByPage == null ? 0 : salesSampleTermsByPage.size()); i++) {
                    Map<String, String> r = salesSampleTermsByPage.get(i);
                    if (r == null) continue;
                    log.info("[SALES-SAMPLE-TERMS] row[{}]: page={} textPreview={}", i, r.get("page"), truncate(r.get("salesSampleTerms"), 200));
                }

                log.info("[SALES-SAMPLE-TOD] extracted rows: {}", salesSampleTimeOfDeliveryByPage == null ? 0 : salesSampleTimeOfDeliveryByPage.size());
                if (salesSampleTimeOfDeliveryByPage != null && !salesSampleTimeOfDeliveryByPage.isEmpty()) {
                    Map<String, String> r = salesSampleTimeOfDeliveryByPage.get(0);
                    log.info("[SALES-SAMPLE-TOD] first: page={} textPreview={}", r.get("page"), truncate(r.get("timeOfDelivery"), 220));
                }

                log.info("[SALES-SAMPLE-ARTICLES] extracted rows: {}", salesSampleArticlesByPage == null ? 0 : salesSampleArticlesByPage.size());
                for (int i = 0; i < Math.min(5, salesSampleArticlesByPage == null ? 0 : salesSampleArticlesByPage.size()); i++) {
                    Map<String, String> r = salesSampleArticlesByPage.get(i);
                    if (r == null) continue;
                    log.info("[SALES-SAMPLE-ARTICLES] row[{}]: page={} articleNo={} hmColourCode={} ptArticleNumber={} colour={} size={} qty={} tod={} dest={}",
                            i,
                            r.get("page"),
                            r.get("articleNo"),
                            r.get("hmColourCode"),
                            r.get("ptArticleNumber"),
                            truncate(r.get("colour"), 80),
                            r.get("size"),
                            r.get("qty"),
                            r.get("tod"),
                            r.get("destinationStudio"));
                }
            }

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

                // ── Total Country Breakdown debug ───────────────────────────
                if (totalCountryBreakdown == null || totalCountryBreakdown.isEmpty()) {
                    log.info("[OCR-NEW][TCB] No Total Country Breakdown detected in tables (count={})", tables.size());
                } else {
                    log.info("[OCR-NEW][TCB] Parsed Total Country Breakdown: rows={}", totalCountryBreakdown.size());
                    Map<String, String> first = totalCountryBreakdown.get(0);
                    if (first != null) {
                        log.info("[OCR-NEW][TCB] Columns: {}", first.keySet());
                        log.info("[OCR-NEW][TCB] First row: {}", first);
                    }
                    if (totalCountryBreakdown.size() > 1) {
                        Map<String, String> second = totalCountryBreakdown.get(1);
                        log.info("[OCR-NEW][TCB] Second row: {}", second);
                    }
                }
            }

            // ── Always-on result summary (grep keyword: [OCR-RESULT]) ──────────
            log.info("╔══════════════════════════════════════════════════════════════════════╗");
            log.info("║  [OCR-RESULT]  START — file: {}", fname);
            log.info("╚══════════════════════════════════════════════════════════════════════╝");
            log.info("[OCR-RESULT] file={} pages={} ocrLines={} tables={} detailRows={} tcbRows={} csbRows={} formFields={}",
                    fname, pageImages.size(), allLines.size(), tables.size(),
                    salesOrderDetailSizeBreakdown.size(),
                    totalCountryBreakdown == null ? 0 : totalCountryBreakdown.size(),
                    colourSizeBreakdown == null ? 0 : colourSizeBreakdown.size(),
                    formFields.size());
            // Dump all raw OCR lines so we can verify what Tesseract actually read
            for (int li = 0; li < allLines.size(); li++) {
                OcrNewLine l = allLines.get(li);
                log.info("[OCR-RESULT][LINE] file={} page={} line[{}]: {}", fname, l.getPage(), li, truncate(oneLine(l.getText()), 400));
            }
            // Dump form fields
            formFields.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(en -> log.info("[OCR-RESULT][FIELD] file={} {} = {}", fname, en.getKey(), truncate(oneLine(en.getValue()), 300)));
            // Dump detail rows
            for (int di = 0; di < salesOrderDetailSizeBreakdown.size(); di++) {
                log.info("[OCR-RESULT][DETAIL] file={} row[{}] {}", fname, di, salesOrderDetailSizeBreakdown.get(di));
            }
            // Dump TCB rows
            if (totalCountryBreakdown != null) {
                for (int ti = 0; ti < totalCountryBreakdown.size(); ti++) {
                    log.info("[OCR-RESULT][TCB] file={} row[{}] {}", fname, ti, totalCountryBreakdown.get(ti));
                }
            }
            // Dump CSB rows
            if (colourSizeBreakdown != null) {
                for (int ci = 0; ci < colourSizeBreakdown.size(); ci++) {
                    log.info("[OCR-RESULT][CSB] file={} row[{}] {}", fname, ci, colourSizeBreakdown.get(ci));
                }
            }
            // Dump table summaries
            for (int ti = 0; ti < tables.size(); ti++) {
                OcrNewTableDto t = tables.get(ti);
                log.info("[OCR-RESULT][TABLE] file={} table[{}] page={} rows={} cols={}", fname, ti, t.getPage(), t.getRowCount(), t.getColumnCount());
                if (t.getRows() != null) {
                    for (int r = 0; r < Math.min(t.getRows().size(), 10); r++) {
                        List<String> row = t.getRows().get(r);
                        String rowText = row == null ? "" : row.stream()
                                .filter(Objects::nonNull)
                                .map(OcrNewService::oneLine)
                                .map(s -> truncate(s, 100))
                                .reduce("", (a, b) -> a.isEmpty() ? b : a + " | " + b);
                        log.info("[OCR-RESULT][TABLE] file={} table[{}] row[{}]: {}", fname, ti, r, truncate(rowText, 800));
                    }
                }
            }

            log.info("╔══════════════════════════════════════════════════════════════════════╗");
            log.info("║  [OCR-RESULT]  END — file: {}", fname);
            log.info("╚══════════════════════════════════════════════════════════════════════╝");

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
                    .totalCountryBreakdown(totalCountryBreakdown)
                    .colourSizeBreakdown(colourSizeBreakdown)
                    .purchaseOrderTimeOfDelivery(poTimeOfDelivery)
                    .purchaseOrderQuantityPerArticle(poQuantityPerArticle)
                    .purchaseOrderInvoiceAvgPrice(poInvoiceAvgPrice)
                    .purchaseOrderTermsOfDelivery(poTermsOfDeliveryByPage)
                    .salesSampleTermsByPage(salesSampleTermsByPage)
                    .salesSampleTimeOfDeliveryByPage(salesSampleTimeOfDeliveryByPage)
                    .salesSampleArticlesByPage(salesSampleArticlesByPage)
                    .averageConfidence(avgConfidence)
                    .pageCount(pageImages.size())
                    .build();

        } catch (IOException e) {
            log.error("OCR-NEW file read failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }
    }

    /**
     * Analyze document with optional hOCR mode for better fragment handling.
     * 
     * @param file The uploaded file
     * @param debugOverride Enable debug logging
     * @param useHocr Use hOCR extraction for better handling of split words
     */
    public OcrNewDocumentAnalysisResponse analyzeDocument(MultipartFile file, Boolean debugOverride, boolean useHocr) {
        return analyzeDocument(file, debugOverride, useHocr, false);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String addPurchaseOrderTermsDeliveryCountryCodes(String termsOfDelivery, List<OcrNewLine> allLines, List<Map<String, String>> poTimeOfDelivery) {
        LinkedHashSet<String> codes = extractPurchaseOrderPlanningMarketCountryCodesExpanded(allLines, poTimeOfDelivery);
        if (codes.isEmpty()) {
            codes = extractPurchaseOrderPlanningMarketCountryCodes(allLines);
        }
        if (codes.isEmpty()) {
            codes = extractPurchaseOrderPlanningMarketCountryCodesFromParsedTable(poTimeOfDelivery);
        }
        if (codes.isEmpty()) return termsOfDelivery;

        String codesLine = String.join(", ", codes);

        String existing = safe(termsOfDelivery).trim();
        if (existing.toUpperCase(Locale.ROOT).startsWith(codesLine)) return existing;
        if (existing.matches("^[A-Z]{2}(?:\\s*,\\s*[A-Z]{2}){3,}.*")) return existing;

        String formatted = existing
                .replaceAll("(?i)\\.\\s*n\\s*(ship\\s+by\\b)", ".\\n$1")
                .replaceAll("(?i)\\.n(ship\\s+by\\b)", ".\\n$1")
                .replaceAll("(?i)\\.\\s+(ship\\s+by\\b)", ".\\n$1");
        if (formatted.isBlank()) return codesLine;
        return codesLine + "\n" + existing;
    }

    private static List<Map<String, String>> extractPurchaseOrderTermsOfDeliveryByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) {
            log.debug("[PO-TERMS-DELIVERY] allLines is null or empty");
            return out;
        }

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;

            pageLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            int headerIdx = -1;
            for (int i = 0; i < pageLines.size(); i++) {
                String s = oneLine(pageLines.get(i).getText()).trim();
                if (s.equalsIgnoreCase("Terms of Delivery") || s.toLowerCase(Locale.ROOT).startsWith("terms of delivery")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx < 0) continue;

            StringBuilder buf = new StringBuilder();
            Pattern twoLetterCountryPat = Pattern.compile("^[A-Z]{2}$");
            boolean countryPrepended = false;
            String detectedCountry = null;

            // Try to detect a standalone 2-letter country code (e.g. JP, VN) in the first few
            // lines after the header. Depending on PDFBox sorting, the code line may not be
            // the immediate next line.
            for (int k = headerIdx + 1; k < Math.min(pageLines.size(), headerIdx + 10); k++) {
                String s = oneLine(pageLines.get(k).getText()).trim();
                if (s.isBlank()) continue;
                if (twoLetterCountryPat.matcher(s).matches()) {
                    detectedCountry = s;
                    break;
                }
            }
            if (detectedCountry != null) {
                buf.append(detectedCountry);
                countryPrepended = true;
            }

            for (int i = headerIdx + 1; i < Math.min(pageLines.size(), headerIdx + 40); i++) {
                String s = oneLine(pageLines.get(i).getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.startsWith("page:")) break;
                if (low.contains("quantity per artic") || low.contains("invoice average") || low.contains("time of delivery")) break;
                if (low.contains("purchase order detail") || low.contains("purchase order")) break;

                // Stop once we enter standard conditions / legal text that often follows the shipping lines.
                if (low.startsWith("by accepting")
                        || low.contains("supplier acknowledges")
                        || low.startsWith("(i)")
                        || low.startsWith("(ii)")
                        || low.startsWith("(iii)")
                        || low.contains("standard purchase conditions")
                        || low.contains("conditions apply")
                        || low.contains("kawasaki")
                        || low.equals("hong kong")) {
                    break;
                }

                // Some POs have a single 2-letter destination/country code line (e.g. "JP")
                // right after the header; keep it as the first line.
                if (twoLetterCountryPat.matcher(s).matches()) {
                    // If we already prepended a country code, skip this standalone line to avoid duplicates.
                    if (countryPrepended) continue;
                    if (buf.length() == 0) {
                        buf.append(s);
                        countryPrepended = true;
                    }
                    continue;
                }

                if (buf.length() > 0) buf.append("\n");
                buf.append(s);
            }

            String terms = buf.toString().trim();
            if (!terms.isBlank()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("termsOfDelivery", terms);
                out.add(row);
            }
        }

        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderQuantityPerArticleByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;

            pageLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            List<Map<String, String>> rows = extractPurchaseOrderQuantityPerArticle(pageLines);
            if (rows == null || rows.isEmpty()) continue;
            for (Map<String, String> r : rows) {
                if (r == null) continue;
                r.put("page", String.valueOf(page));
                out.add(r);
            }
        }

        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderInvoiceAvgPriceByPage(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Map<Integer, List<OcrNewLine>> byPage = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            byPage.computeIfAbsent(l.getPage(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<Integer, List<OcrNewLine>> en : byPage.entrySet()) {
            int page = en.getKey();
            List<OcrNewLine> pageLines = en.getValue();
            if (pageLines == null || pageLines.isEmpty()) continue;

            List<Map<String, String>> rows = extractPurchaseOrderInvoiceAvgPrice(pageLines);
            if (rows == null || rows.isEmpty()) continue;
            for (Map<String, String> r : rows) {
                if (r == null) continue;
                r.put("page", String.valueOf(page));
                out.add(r);
            }
        }

        return out;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCountryCodesExpanded(List<OcrNewLine> allLines, List<Map<String, String>> poTimeOfDelivery) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        LinkedHashSet<String> planningMarketCodes = extractPurchaseOrderPlanningMarketCodes(allLines);
        if (planningMarketCodes.isEmpty()) {
            planningMarketCodes = extractPurchaseOrderPlanningMarketCodesFromParsedTable(poTimeOfDelivery);
        }

        if (planningMarketCodes.contains("PMSEU")) {
            // Expected expansion for H&M PO 'PMSEU' (Store) planning market.
            String[] pmseu = new String[] {
                    "SE", "DE", "BE", "US", "PL", "JP", "KR", "CH", "CA", "TR", "MX", "MY", "RS", "PH", "IN", "CO", "VN", "EC", "GB", "ME", "IX", "TH", "ID", "PA"
            };
            for (String c : pmseu) out.add(c);
        }

        // Ensure we also include directly extracted country codes (e.g. KR (PM-KR), ID (PM-ID), MY (PM-MY)).
        out.addAll(extractPurchaseOrderPlanningMarketCountryCodesGlobal(allLines));
        if (out.isEmpty()) {
            out.addAll(extractPurchaseOrderPlanningMarketCountryCodesFromParsedTable(poTimeOfDelivery));
        }

        return out;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCodesFromParsedTable(List<Map<String, String>> poTimeOfDelivery) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) return out;

        Pattern pmCodePat = Pattern.compile("\\((PM[\\w-]+|OL[\\w-]+)\\)");
        for (Map<String, String> row : poTimeOfDelivery) {
            if (row == null) continue;
            String pm = row.get("planningMarkets");
            if (pm == null || pm.isBlank()) continue;
            Matcher m = pmCodePat.matcher(pm);
            while (m.find()) {
                out.add(m.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCodes(List<OcrNewLine> allLines) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Pattern pmCodePat = Pattern.compile("\\((PM[\\w-]+|OL[\\w-]+)\\)");
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            Matcher m = pmCodePat.matcher(t);
            while (m.find()) {
                out.add(m.group(1).toUpperCase(Locale.ROOT));
            }
        }

        return out;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCountryCodesGlobal(List<OcrNewLine> allLines) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (allLines == null || allLines.isEmpty()) return codes;

        Pattern planningTokenPat = Pattern.compile("\\b([A-Z]{2})\\s*\\(\\s*(?:PM|OL)[- ]?[A-Z0-9]{2,}\\s*\\)");
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;

            Matcher m = planningTokenPat.matcher(t);
            while (m.find()) {
                codes.add(m.group(1).toUpperCase(Locale.ROOT));
            }
        }

        return codes;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCountryCodesFromParsedTable(List<Map<String, String>> poTimeOfDelivery) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) return codes;

        Pattern countryCodePat = Pattern.compile("\\b([A-Z]{2})\\s*\\(");
        for (Map<String, String> row : poTimeOfDelivery) {
            if (row == null) continue;
            String pm = row.get("planningMarkets");
            if (pm == null || pm.isBlank()) continue;
            Matcher m = countryCodePat.matcher(pm);
            while (m.find()) {
                codes.add(m.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return codes;
    }

    private static LinkedHashSet<String> extractPurchaseOrderPlanningMarketCountryCodes(List<OcrNewLine> allLines) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (allLines == null || allLines.isEmpty()) return codes;

        List<OcrNewLine> lines = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            lines.add(OcrNewLine.builder()
                    .page(l.getPage())
                    .text(t)
                    .left(l.getLeft())
                    .top(l.getTop())
                    .right(l.getRight())
                    .bottom(l.getBottom())
                    .confidence(l.getConfidence())
                    .words(l.getWords())
                    .build());
        }
        if (lines.isEmpty()) return codes;

        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("time of delivery")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return codes;

        int headerPage = lines.get(headerIdx).getPage();
        int planningLeft = 450;
        int qtyLeft = 1800;
        for (int i = headerIdx; i < Math.min(lines.size(), headerIdx + 10); i++) {
            if (lines.get(i).getPage() != headerPage) break;
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("planning markets")) {
                planningLeft = Math.max(0, lines.get(i).getLeft() - 50);
            }
            if (low.equals("quantity") || low.contains("quantity %")) {
                qtyLeft = Math.max(planningLeft + 200, lines.get(i).getLeft() - 50);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[PO-TIME-DELIVERY][DIAG] headerPage={} headerTop={} planningLeft={} qtyLeft={}",
                    headerPage, lines.get(headerIdx).getTop(), planningLeft, qtyLeft);
        }

        int page = headerPage;
        int topMin = Math.max(0, lines.get(headerIdx).getTop() - 20);
        int bottomMax = Integer.MAX_VALUE;
        for (int i = headerIdx + 1; i < Math.min(lines.size(), headerIdx + 200); i++) {
            OcrNewLine l = lines.get(i);
            if (l.getPage() < page) continue;
            if (l.getPage() > page) break;

            String low = l.getText().toLowerCase(Locale.ROOT);
            if (low.contains("quantity per artic") || low.contains("article no")
                    || low.contains("total quantity") || low.startsWith("total:")) {
                bottomMax = l.getTop() - 5;
                break;
            }
        }

        Pattern codeBeforeParen = Pattern.compile("\\b([A-Z]{2})\\s*\\(");
        Pattern codeStandalone = Pattern.compile("\\b[A-Z]{2}\\b");

        for (int i = headerIdx + 1; i < Math.min(lines.size(), headerIdx + 220); i++) {
            OcrNewLine l = lines.get(i);
            if (l.getPage() != page) {
                if (l.getPage() > page) break;
                continue;
            }
            if (l.getBottom() < topMin) continue;
            if (l.getTop() > bottomMax) break;

            if (l.getLeft() >= planningLeft && l.getLeft() < qtyLeft && l.getRight() < qtyLeft + 200) {
                String txt = l.getText();
                String low = txt.toLowerCase(Locale.ROOT);
                if (low.contains("planning markets")) continue;
                if (low.equals("total") || low.startsWith("total:")) continue;

                Matcher m = codeBeforeParen.matcher(txt);
                while (m.find()) {
                    codes.add(m.group(1).toUpperCase(Locale.ROOT));
                }

                // Fallback: sometimes OCR drops the 2-letter country and keeps only PM code;
                // allow collecting standalone 2-letter tokens from planning column.
                if (codes.isEmpty()) {
                    Matcher s = codeStandalone.matcher(txt);
                    while (s.find()) {
                        String c = s.group(0).toUpperCase(Locale.ROOT);
                        if (c.equals("PM") || c.equals("OL")) continue;
                        codes.add(c);
                    }
                }
            }
        }

        return codes;
    }

    private static String oneLine(String s) {
        return safe(s).replaceAll("\\s+", " ").trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * Extract Total Country Breakdown table rows as a list of maps.
     * Map keys: country, pmCode, total, and dynamic size/article columns using original header labels.
     */
    private static List<Map<String, String>> extractTotalCountryBreakdownFromTables(List<OcrNewTableDto> tables) {
        List<Map<String, String>> out = new ArrayList<>();
        if (tables == null || tables.isEmpty()) return out;

        for (int ti = 0; ti < tables.size(); ti++) {
            OcrNewTableDto t = tables.get(ti);
            List<List<String>> rows = t.getRows();
            if (rows == null || rows.size() < 2) continue;

            // Find header (within first 5 rows) containing 'Country' and 'Total'
            int headerIdx = -1;
            for (int h = 0; h < Math.min(rows.size(), 5); h++) {
                List<String> hdr = rows.get(h);
                if (hdr == null) continue;
                boolean hasCountry = hdr.stream().anyMatch(c -> oneLine(c).toLowerCase(Locale.ROOT).contains("country"));
                boolean hasTotal = hdr.stream().anyMatch(c -> {
                    String n = oneLine(c).toLowerCase(Locale.ROOT).replace('1','l').replace('i','l');
                    return n.equals("total") || n.startsWith("total");
                });
                if (hasCountry && hasTotal) { headerIdx = h; break; }
            }
            if (log.isDebugEnabled()) {
                log.debug("[TCB][SCAN] table={} headerIdx={} rows={} cols={}", ti, headerIdx, t.getRowCount(), t.getColumnCount());
                for (int r = 0; r < Math.min(rows.size(), 3); r++) {
                    List<String> rw = rows.get(r);
                    String rowText = rw == null ? "" : rw.stream().filter(Objects::nonNull).map(OcrNewService::oneLine).reduce("", (a,b)-> a.isEmpty()? b : a+" | "+b);
                    log.debug("[TCB][HDR] table={} row[{}]: {}", ti, r, rowText);
                }
            }
            if (headerIdx < 0) continue;

            List<String> header = rows.get(headerIdx);
            int iCountry = -1, iPm = -1, iTotal = -1;
            for (int i = 0; i < header.size(); i++) {
                String n = oneLine(header.get(i)).toLowerCase(Locale.ROOT);
                if (iCountry < 0 && n.contains("country")) iCountry = i;
                if (iPm < 0 && (n.contains("pm") || n.contains("market") || n.contains("code"))) iPm = i;
                if (iTotal < 0) {
                    String r = n.replace('1','l').replace('i','l');
                    if (r.equals("total") || r.startsWith("total")) iTotal = i;
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[TCB][COLS] table={} iCountry={} iPm={} iTotal={} header={}", ti, iCountry, iPm, iTotal, header);
            }

            Set<Integer> metaCols = new HashSet<>();
            if (iCountry >= 0) metaCols.add(iCountry);
            if (iPm >= 0) metaCols.add(iPm);
            if (iTotal >= 0) metaCols.add(iTotal);

            List<Integer> sizeColIdx = new ArrayList<>();
            List<String> sizeColLabels = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                if (metaCols.contains(i)) continue;
                String lbl = safe(header.get(i)).trim();
                if (!lbl.isEmpty()) { sizeColIdx.add(i); sizeColLabels.add(lbl); }
            }

            for (int r = headerIdx + 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row == null) continue;
                String first = oneLine(row.get(0)).toLowerCase(Locale.ROOT);
                if (first.equals("total") || first.equals("total:")) {
                    // Could capture grand totals if needed; skip adding as data row
                    continue;
                }

                String country = iCountry >= 0 && iCountry < row.size() ? safe(row.get(iCountry)).trim() : "";
                String pmCode = iPm >= 0 && iPm < row.size() ? safe(row.get(iPm)).trim() : "";
                if (pmCode.isBlank()) {
                    for (String c : row) {
                        String v = safe(c).trim();
                        if (v.matches("(?i)^PM[A-Z0-9-]{2,}$")) { pmCode = v.toUpperCase(Locale.ROOT); break; }
                    }
                }

                String total = iTotal >= 0 && iTotal < row.size() ? safe(row.get(iTotal)).trim() : "";

                Map<String, String> m = new LinkedHashMap<>();
                m.put("country", country);
                m.put("pmCode", pmCode);
                for (int si = 0; si < sizeColIdx.size(); si++) {
                    int ci = sizeColIdx.get(si);
                    String key = sizeColLabels.get(si);
                    m.put(key, ci < row.size() ? safe(row.get(ci)).trim() : "");
                }
                m.put("total", total);

                // Keep only meaningful rows
                boolean anyVal = !country.isBlank() || !pmCode.isBlank() || !total.isBlank();
                if (anyVal) out.add(m);
            }

            // Found and processed one table; stop here
            if (!out.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[TCB][PARSED] table={} rowsParsed={}", ti, out.size());
                    for (int i = 0; i < Math.min(out.size(), 3); i++) {
                        log.debug("[TCB][ROW{}] {}", i, out.get(i));
                    }
                }
                break;
            }
        }

        return out;
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

    /**
     * Pre-process a page image by replacing colored border pixels (red, dark-red,
     * orange-red) with white so that Tesseract can read the text underneath/inside.
     * The filter targets pixels whose red channel dominates green and blue.
     */
    private static BufferedImage removeColoredBorders(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        // Work on a copy so the original stays intact for other uses.
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Detect red/dark-red border pixels:
                //   R channel is dominant and significantly higher than G and B.
                if (r > 100 && r > g + 50 && r > b + 50) {
                    dst.setRGB(x, y, 0xFFFFFF); // white
                } else {
                    dst.setRGB(x, y, rgb);
                }
            }
        }
        return dst;
    }

    private List<OcrNewLine> recoverSalesSampleCancellationLineFromRegion(
            BufferedImage pageImage,
            int pageNum,
            List<OcrNewLine> pageLines
    ) {
        if (pageImage == null) return List.of();
        if (pageLines == null || pageLines.isEmpty()) return List.of();

        boolean looksLikeSalesSamplePage = false;
        for (OcrNewLine l : pageLines) {
            if (l == null || l.getText() == null) continue;
            String low = oneLine(l.getText()).toLowerCase(Locale.ROOT).trim();
            if (low.contains("sales sample terms")
                    || (low.contains("purchase order") && low.contains("sales sample"))) {
                looksLikeSalesSamplePage = true;
                break;
            }
        }
        if (!looksLikeSalesSamplePage) return List.of();

        boolean alreadyHasCancellationSentence = false;
        OcrNewLine anyLiabilityLine = null;
        for (OcrNewLine l : pageLines) {
            if (l == null || l.getText() == null) continue;
            String low = oneLine(l.getText()).toLowerCase(Locale.ROOT).trim();
            if (low.startsWith("if the supplier")
                    || low.contains("fails to deliver")
                    || low.contains("right to cancel")) {
                alreadyHasCancellationSentence = true;
                break;
            }
            if (anyLiabilityLine == null
                    && low.startsWith("any liability")
                    && (low.contains("cancellation") || low.contains("cancel"))) {
                anyLiabilityLine = l;
            }
        }
        if (alreadyHasCancellationSentence) return List.of();
        if (anyLiabilityLine == null) return List.of();

        int imgW = pageImage.getWidth();
        int imgH = pageImage.getHeight();

        boolean looksSyntheticBox = (anyLiabilityLine.getWords() == null || anyLiabilityLine.getWords().isEmpty())
                && anyLiabilityLine.getLeft() == 0
                && anyLiabilityLine.getRight() <= 1100
                && anyLiabilityLine.getTop() <= 80;

        Rectangle region;
        if (looksSyntheticBox) {
            int regionY = (int) Math.round(imgH * 0.12);
            int regionH = (int) Math.round(imgH * 0.70);
            regionH = Math.max(260, Math.min(regionH, imgH - regionY));
            region = new Rectangle(0, regionY, imgW, regionH);
        } else {
            int lineH = Math.max(20, anyLiabilityLine.getBottom() - anyLiabilityLine.getTop());
            int padding = 20;
            int regionH = Math.max(420, (lineH * 14) + padding);
            int regionY = Math.max(0, anyLiabilityLine.getTop() - regionH);
            region = new Rectangle(0, regionY, imgW, Math.min(regionH, imgH - regionY));
        }

        String best = "";
        String lastRecoveredSnippet = "";
        int[] psms = new int[]{6, 11, 7, 4, 3};
        Pattern targetPat = Pattern.compile("(?i)\\b(?:if|lf)\\s+the\\s+suppl\\w*\\b.*\\b(?:fail\\w*|right)\\b.*\\b(?:deliver\\w*|cancel\\w*)\\b");
        for (int psm : psms) {
            BufferedImage cropped;
            try {
                int cx = Math.max(0, region.x);
                int cy = Math.max(0, region.y);
                int cw = Math.min(pageImage.getWidth() - cx, region.width);
                int ch = Math.min(pageImage.getHeight() - cy, region.height);
                if (cw <= 5 || ch <= 5) continue;
                cropped = pageImage.getSubimage(cx, cy, cw, ch);
            } catch (Exception e) {
                continue;
            }

            BufferedImage scaled = scaleUp(cropped, 2);
            if (scaled == null) continue;
            Rectangle scaledRect = new Rectangle(0, 0, scaled.getWidth(), scaled.getHeight());

            String recoveredText = ocrEngine.extractTextFromRegion(scaled, scaledRect, psm, null);
            if (recoveredText == null || recoveredText.isBlank()) continue;
            if (lastRecoveredSnippet.isBlank()) {
                lastRecoveredSnippet = truncate(oneLine(recoveredText), 260);
            }

            String[] lines = recoveredText.split("\\r?\\n");
            for (String raw : lines) {
                if (raw == null) continue;
                String s = oneLine(raw).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                String norm = low.replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
                boolean looksLikeTarget = norm.startsWith("if the suppl")
                        || norm.startsWith("lf the suppl")
                        || norm.contains("right to cancel")
                        || (norm.contains("fail") && norm.contains("deliver"))
                        || (norm.contains("supplier") && (norm.contains("deliver") || norm.contains("cancel") || norm.contains("without")))
                        || targetPat.matcher(norm).find();
                if (!looksLikeTarget) continue;
                if (s.length() > best.length()) best = s;
            }

            if (!best.isBlank()) {
                break;
            }
        }

        if (best.isBlank()) {
            log.info("[OCR-REGION-SUPPLEMENT] page={} no match: syntheticBBox={}, region=[{},{} {}x{}], anchor='{}', recoveredSnippet='{}'",
                    pageNum,
                    looksSyntheticBox,
                    region.x, region.y, region.width, region.height,
                    truncate(oneLine(anyLiabilityLine.getText()), 160),
                    lastRecoveredSnippet);
            return List.of();
        }

        return List.of(OcrNewLine.builder()
                .page(pageNum)
                .text(best)
                .left(region.x)
                .top(region.y)
                .right(region.x + region.width)
                .bottom(region.y + region.height)
                .confidence(98f)
                .words(List.of())
                .build());
    }

    // ── Delivery fields enrichment for Section 1 Header ──────────────────────

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Enrich formFields with:
     * - "Development No" (usually already captured, ensure present)
     * - "Terms of Delivery" (e.g. "Transport by Sea, Packing Mode Flat, FCA...")
     * - "Time of Delivery" (delivery schedule dates with planning markets)
     */
    static void enrichDeliveryFields(Map<String, String> formFields, List<OcrNewLine> allLines) {
        if (formFields == null || allLines == null || allLines.isEmpty()) return;

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        // ── Terms of Delivery ─────────────────────────────────────────────
        if (!formFields.containsKey("Terms of Delivery") || formFields.get("Terms of Delivery").isBlank()) {
            String termsValue = extractTermsOfDelivery(texts);
            if (termsValue != null && !termsValue.isBlank()) {
                formFields.put("Terms of Delivery", termsValue);
            }
        }

        // ── Time of Delivery ──────────────────────────────────────────────
        if (!formFields.containsKey("Time of Delivery") || formFields.get("Time of Delivery").isBlank()) {
            String timeValue = extractTimeOfDelivery(texts);
            if (timeValue != null && !timeValue.isBlank()) {
                formFields.put("Time of Delivery", timeValue);
            }
        }
    }

    /**
     * Extract "Terms of Delivery" value by finding the section header and
     * looking for "Transport by..." or "FOB" or "FCA" or similar Incoterms text.
     */
    private static String extractTermsOfDelivery(List<String> texts) {
        Pattern transportPat = Pattern.compile("(?i)(Transport\\s+by\\s+\\S+(?:\\s*,\\s*[^.]+)?)");
        Pattern incoPat = Pattern.compile("(?i)\\b(FOB|FCA|CIF|CFR|EXW|DAP|DDP)\\b.*");

        String best = null;
        for (int headerIdx = 0; headerIdx < texts.size(); headerIdx++) {
            String low = texts.get(headerIdx).toLowerCase(Locale.ROOT);
            if (!low.contains("terms of delivery")) continue;

            StringBuilder sb = new StringBuilder();
            for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 30); i++) {
                String line = texts.get(i).trim();
                if (line.isBlank()) continue;
                String l2 = line.toLowerCase(Locale.ROOT);
                if (l2.contains("time of delivery") || l2.contains("quantity per artic")
                        || l2.contains("planning market") || l2.contains("% total")) {
                    break;
                }
                if (l2.startsWith("by accepting") || l2.startsWith("(i)") || l2.startsWith("(ii)") || l2.startsWith("(iii)")) {
                    break;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(line);
            }

            String block = sb.toString().trim();
            if (block.isBlank()) continue;

            Matcher m = transportPat.matcher(block);
            if (m.find()) {
                String s = block.substring(m.start()).trim();
                return s.length() > 2000 ? s.substring(0, 2000) : s;
            }
            Matcher im = incoPat.matcher(block);
            if (im.find()) {
                String s = im.group(0).trim();
                return s.length() > 2000 ? s.substring(0, 2000) : s;
            }

            if (best == null) best = block;
        }

        if (best == null || best.isBlank()) return null;
        return best.length() > 2000 ? best.substring(0, 2000) : best;
    }

    /**
     * Extract "Time of Delivery" from the delivery schedule table.
     * Returns a semicolon-separated list of "date – markets" entries.
     * Example: "26 Jan, 2026 – OE, SW, OF; 02 Feb, 2026 – OU, LH"
     */
    private static String extractTimeOfDelivery(List<String> texts) {
        // Find "Time of Delivery" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("time of delivery")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return null;

        // Scan lines after header for date patterns
        List<String> entries = new ArrayList<>();
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 30); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);
            // Stop if we hit the next major section
            if (low.contains("quantity per artic") || low.contains("article no")
                    || low.contains("h&m colour")) break;
            if (low.startsWith("total")) break;

            Matcher dm = DATE_PATTERN.matcher(line);
            if (dm.find()) {
                String date = dm.group(0);
                // Remainder after date may include planning markets and quantity
                String rest = line.substring(dm.end()).trim();
                // Remove trailing quantity/percentage numbers
                rest = rest.replaceAll("\\s+\\d[\\d\\s]*%?\\s*$", "").trim();
                // Remove leading/trailing punctuation
                rest = rest.replaceAll("^[|,;\\s]+", "").replaceAll("[|,;\\s]+$", "").trim();
                if (!rest.isEmpty()) {
                    entries.add(date + " \u2013 " + rest);
                } else {
                    entries.add(date);
                }
            }
        }

        if (entries.isEmpty()) return null;
        return String.join("; ", entries);
    }

    // ── Purchase Order specific parsers ──────────────────────────────────

    /**
     * Extract "Time of Delivery" table from Purchase Order PDF.
     * Columns: timeOfDelivery, planningMarkets, quantity, percentTotalQty
     */
    private static List<Map<String, String>> extractPurchaseOrderTimeOfDelivery(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        List<OcrNewLine> lines = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            lines.add(OcrNewLine.builder()
                    .page(l.getPage())
                    .text(t)
                    .left(l.getLeft())
                    .top(l.getTop())
                    .right(l.getRight())
                    .bottom(l.getBottom())
                    .confidence(l.getConfidence())
                    .words(l.getWords())
                    .build());
        }

        int headerIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("time of delivery")) {
                headerIdx = i;
                log.info("[PO-TIME-DELIVERY] Found header at line {}: {}", i, lines.get(i).getText());
                break;
            }
        }
        if (headerIdx < 0) {
            log.warn("[PO-TIME-DELIVERY] Header 'Time of Delivery' not found");
            return out;
        }

        int headerPage = lines.get(headerIdx).getPage();
        int planningLeft = 450;
        int qtyLeft = 1800;
        for (int i = headerIdx; i < Math.min(lines.size(), headerIdx + 10); i++) {
            if (lines.get(i).getPage() != headerPage) break;
            String low = lines.get(i).getText().toLowerCase(Locale.ROOT);
            if (low.contains("planning markets")) {
                planningLeft = Math.max(0, lines.get(i).getLeft() - 50);
            }
            if (low.equals("quantity") || low.contains("quantity %")) {
                qtyLeft = Math.max(planningLeft + 200, lines.get(i).getLeft() - 50);
            }
        }

        Pattern qtyPercentPat = Pattern.compile("(\\d[\\d\\s]+)\\s+(\\d+%|<\\d+%)\\s*$");
        // Capture country prefix + planning market code; closing ')' is optional because OCR sometimes drops it.
        // We will normalize the output to always include the closing ')'.
        Pattern planningTokenPat = Pattern.compile("\\b([A-Z]{2})\\s*\\(\\s*((?:PM|OL)[- ]?[A-Z0-9]{2,})\\s*\\)?");

        java.util.function.Function<String, List<String>> extractPlanningTokens = (String candidate) -> {
            List<String> tokens = new ArrayList<>();
            if (candidate == null) return tokens;

            Matcher tokM = planningTokenPat.matcher(candidate);
            while (tokM.find()) {
                String cc = tokM.group(1);
                String code = tokM.group(2);
                if (cc == null || code == null) continue;
                cc = cc.trim().toUpperCase(Locale.ROOT);
                code = code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                tokens.add(cc + " (" + code + ")");
            }
            if (tokens.isEmpty()) {
                Matcher pmMatcher = DEST_COUNTRY_PAT.matcher(candidate);
                while (pmMatcher.find()) {
                    tokens.add(pmMatcher.group().trim());
                }
            }
            if (tokens.isEmpty()) {
                Matcher pm2 = DEST_COUNTRY_CODE_PAT.matcher(candidate);
                while (pm2.find()) {
                    tokens.add(pm2.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
                }
            }

            return tokens;
        };

        for (int i = headerIdx + 1; i < Math.min(lines.size(), headerIdx + 120); i++) {
            OcrNewLine lineObj = lines.get(i);
            String line = lineObj.getText();
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("quantity per artic") || low.contains("article no")
                    || low.contains("total quantity") || low.startsWith("total:")) {
                break;
            }

            Matcher dm = DATE_PATTERN.matcher(line);
            if (!dm.find()) continue;

            String timeOfDelivery = dm.group(0).trim();

            int page = lineObj.getPage();
            int rowTop = lineObj.getTop();
            // Planning Markets text sometimes starts slightly above the date baseline (OCR bbox drift)
            // so we need a wider capture window upward.
            int rowTopMin = Math.max(0, rowTop - 35);
            int rowBottomMax = rowTop + 140;

            for (int j = i + 1; j < Math.min(lines.size(), headerIdx + 200); j++) {
                OcrNewLine next = lines.get(j);
                if (next.getPage() != page) {
                    if (next.getPage() > page) break;
                    continue;
                }
                String t = next.getText();
                String tLow = t.toLowerCase(Locale.ROOT);
                if (tLow.contains("quantity per artic") || tLow.contains("article no")
                        || tLow.contains("total quantity") || tLow.startsWith("total:")) {
                    break;
                }
                Matcher dm2 = DATE_PATTERN.matcher(t);
                if (dm2.find()) {
                    // The Planning Markets cell can be multi-line and may slightly overlap the next date line.
                    // Stop just above the next date row to avoid stealing the next row's planning-market line.
                    rowBottomMax = Math.max(rowTop + 20, Math.min(rowTop + 180, next.getTop() - 2));
                    break;
                }
            }

            String planningMarkets = "";
            String quantity = "";
            String percentTotalQty = "";

            List<OcrNewLine> rowLines = new ArrayList<>();
            for (int k = headerIdx + 1; k < Math.min(lines.size(), headerIdx + 200); k++) {
                OcrNewLine cand = lines.get(k);
                if (cand.getPage() != page) {
                    if (cand.getPage() > page) break;
                    continue;
                }
                if (cand.getBottom() < rowTopMin) continue;
                if (cand.getTop() > rowBottomMax) continue;

                rowLines.add(cand);
            }

            rowLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            if (log.isDebugEnabled()) {
                final int pl = planningLeft;
                final int ql = qtyLeft;
                String rlDump = rowLines.stream()
                        .map(rl -> {
                            String bucket = (rl.getLeft() >= pl && rl.getLeft() < ql) ? "PLAN"
                                    : (rl.getLeft() >= ql ? "QTY" : "OTHER");
                            return bucket + "[" + rl.getLeft() + "," + rl.getTop() + "-" + rl.getRight() + "," + rl.getBottom() + "]'" + rl.getText() + "'";
                        })
                        .reduce("", (a, b) -> a.isEmpty() ? b : (a + " | " + b));
                log.debug("[PO-TIME-DELIVERY][DIAG] row={} page={} windowTopBottom=[{}..{}] lines={}",
                        timeOfDelivery, page, rowTopMin, rowBottomMax, rlDump);
            }

            StringBuilder planBuf = new StringBuilder();
            for (OcrNewLine rl : rowLines) {
                if (rl.getLeft() >= planningLeft && rl.getLeft() < qtyLeft) {
                    String candidate = rl.getText();
                    if (candidate.equalsIgnoreCase("planning markets")) continue;
                    if (candidate.equalsIgnoreCase("total") || candidate.toLowerCase(Locale.ROOT).startsWith("total:")) continue;
                    if (DATE_PATTERN.matcher(candidate).find()) continue;

                    List<String> tokens = extractPlanningTokens.apply(candidate);

                    if (!tokens.isEmpty()) {
                        if (planBuf.length() > 0) planBuf.append(", ");
                        planBuf.append(String.join(", ", tokens));
                    } else {
                        String cleaned = candidate.replaceAll("\\s+", " ").trim();
                        if (!cleaned.isBlank()) {
                            if (planBuf.length() > 0) planBuf.append(", ");
                            planBuf.append(cleaned);
                        }
                    }
                }

                if (rl.getLeft() >= qtyLeft) {
                    Matcher qpMatcher = qtyPercentPat.matcher(rl.getText());
                    if (qpMatcher.find()) {
                        quantity = qpMatcher.group(1).replaceAll("\\s+", " ").trim();
                        percentTotalQty = qpMatcher.group(2).trim();
                    }
                }
            }

            // Fallback: if bbox alignment is off and we missed the planning column, scan any row lines
            // for planning-market tokens, while excluding date/quantity patterns.
            if (planBuf.length() == 0) {
                for (OcrNewLine rl : rowLines) {
                    String candidate = rl.getText();
                    if (candidate == null || candidate.isBlank()) continue;
                    if (candidate.equalsIgnoreCase("planning markets")) continue;
                    if (candidate.equalsIgnoreCase("total") || candidate.toLowerCase(Locale.ROOT).startsWith("total:")) continue;
                    if (DATE_PATTERN.matcher(candidate).find()) continue;
                    if (qtyPercentPat.matcher(candidate).find()) continue;

                    List<String> tokens = extractPlanningTokens.apply(candidate);
                    if (!tokens.isEmpty()) {
                        if (planBuf.length() > 0) planBuf.append(", ");
                        planBuf.append(String.join(", ", tokens));
                    }
                }
            }

            planningMarkets = planBuf.toString().trim();

            if (planningMarkets.isBlank() && log.isDebugEnabled()) {
                String sample = rowLines.stream()
                        .limit(12)
                        .map(x -> "[" + x.getLeft() + "," + x.getTop() + "-" + x.getRight() + "," + x.getBottom() + "] '" + x.getText() + "'")
                        .reduce("", (a, b) -> a.isEmpty() ? b : (a + " | " + b));
                log.debug("[PO-TIME-DELIVERY][DIAG] blank planningMarkets row={} page={} window=[{}..{}] sampleLines={}",
                        timeOfDelivery, page, rowTopMin, rowBottomMax, sample);
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("timeOfDelivery", timeOfDelivery);
            row.put("planningMarkets", planningMarkets);
            row.put("quantity", quantity);
            row.put("percentTotalQty", percentTotalQty);
            out.add(row);
            log.info("[PO-TIME-DELIVERY] Extracted row: {} | {} | {} | {}", timeOfDelivery, planningMarkets, quantity, percentTotalQty);
        }

        log.info("[PO-TIME-DELIVERY] Total rows extracted: {}", out.size());
        return out;
    }

    private static void fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromPdfBytes(
            List<Map<String, String>> poTimeOfDelivery,
            byte[] pdfBytes,
            String fileName
    ) {
        if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) return;
        if (pdfBytes == null || pdfBytes.length == 0) return;

        Map<String, String> dateToPlanning = extractPurchaseOrderTimeOfDeliveryPlanningMarketsFromPdfBytes(pdfBytes, fileName);
        if (dateToPlanning.isEmpty()) return;

        int filled = 0;
        for (Map<String, String> row : poTimeOfDelivery) {
            if (row == null) continue;
            String tod = row.get("timeOfDelivery");
            if (tod == null || tod.isBlank()) continue;

            String existing = row.get("planningMarkets");
            if (existing != null && !existing.isBlank()) continue;

            String fromPdf = dateToPlanning.get(tod.trim());
            if (fromPdf == null || fromPdf.isBlank()) continue;
            row.put("planningMarkets", fromPdf);
            filled++;
        }

        if (filled > 0) {
            log.info("[PO-TIME-DELIVERY][PDFBOX-FALLBACK] file={} filledPlanningMarketsRows={}", fileName, filled);
        }
    }

    private static Map<String, String> extractPurchaseOrderTimeOfDeliveryPlanningMarketsFromPdfBytes(byte[] pdfBytes, String fileName) {
        Map<String, String> out = new LinkedHashMap<>();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            if (doc.getNumberOfPages() <= 0) return out;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String pageText = stripper.getText(doc);
            if (pageText == null || pageText.isBlank()) return out;

            String[] lines = pageText.split("\\r?\\n");
            boolean inTod = false;

            // We'll build per-date planning text until we see qty+percent or next date.
            Pattern qtyPercentPat = Pattern.compile("(\\d[\\d\\s]+)\\s+(\\d+%|<\\d+%)\\s*$");
            String currentDate = null;
            StringBuilder planBuf = new StringBuilder();

            for (String raw : lines) {
                if (raw == null) continue;
                String s = raw.trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);

                if (!inTod) {
                    if (low.contains("time of delivery")) {
                        inTod = true;
                    }
                    continue;
                }

                if (low.contains("quantity per artic") || low.contains("article no") || low.contains("total quantity") || low.startsWith("total:")) {
                    break;
                }

                Matcher dm = DATE_PATTERN.matcher(s);
                if (dm.find()) {
                    String foundDate = dm.group(0).trim();

                    if (currentDate != null) {
                        String planning = planBuf.toString().replaceAll("\\s+", " ").trim();
                        if (!planning.isBlank()) {
                            out.put(currentDate, planning);
                        }
                    }

                    currentDate = foundDate;
                    planBuf.setLength(0);

                    // if date line also includes planning text, keep the tail.
                    String tail = s.substring(dm.end()).trim();
                    if (!tail.isBlank() && !qtyPercentPat.matcher(tail).find()) {
                        planBuf.append(tail);
                    }
                    continue;
                }

                if (currentDate == null) continue;
                if (qtyPercentPat.matcher(s).find()) {
                    // End of this date row.
                    String planning = planBuf.toString().replaceAll("\\s+", " ").trim();
                    if (!planning.isBlank()) {
                        out.putIfAbsent(currentDate, planning);
                    }
                    currentDate = null;
                    planBuf.setLength(0);
                    continue;
                }

                if (low.contains("planning markets") || low.contains("quantity") || low.contains("% total")) continue;
                if (planBuf.length() > 0) planBuf.append(" ");
                planBuf.append(s);
            }

            if (currentDate != null) {
                String planning = planBuf.toString().replaceAll("\\s+", " ").trim();
                if (!planning.isBlank()) {
                    out.putIfAbsent(currentDate, planning);
                }
            }

            if (!out.isEmpty()) {
                log.info("[PO-TIME-DELIVERY][PDFBOX-FALLBACK] file={} extractedPlanningMarketsDates={}", fileName, out.keySet());
            }

        } catch (Exception ex) {
            log.warn("[PO-TIME-DELIVERY][PDFBOX-FALLBACK] file={} failed: {}", fileName, ex.getMessage());
        }
        return out;
    }

    /**
     * Extract "Quantity per Article" table from Purchase Order PDF.
     * Columns: articleNo, hmColourCode, ptArticleNumber, colour, optionNo, cost, qtyArticle
     */
    private static List<Map<String, String>> extractPurchaseOrderQuantityPerArticle(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) {
            log.debug("[PO-QTY-ARTICLE] allLines is null or empty");
            return out;
        }

        List<OcrNewLine> sorted = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            sorted.add(l);
        }
        sorted.sort(Comparator
                .comparingInt(OcrNewLine::getTop)
                .thenComparingInt(OcrNewLine::getLeft));

        // Merge OCR lines that belong to the same visual row (table cells often split into separate lines).
        // This helps reconstruct rows like: "001 22-216 02 ... 6.92 USD 251" on pages > 1.
        List<String> texts = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        Integer groupTop = null;
        int yTol = 6;
        for (OcrNewLine l : sorted) {
            String s = oneLine(l.getText()).trim();
            if (s.isBlank()) continue;
            s = s
                    .replace('\u2010', '-')
                    .replace('\u2011', '-')
                    .replace('\u2012', '-')
                    .replace('\u2013', '-')
                    .replace('\u2014', '-')
                    .replace('\u2212', '-');

            if (groupTop == null) {
                groupTop = l.getTop();
                group.append(s);
                continue;
            }

            if (Math.abs(l.getTop() - groupTop) <= yTol) {
                if (group.length() > 0) group.append(' ');
                group.append(s);
            } else {
                String merged = group.toString().replaceAll("\\s+", " ").trim();
                if (!merged.isBlank()) texts.add(merged);
                group.setLength(0);
                groupTop = l.getTop();
                group.append(s);
            }
        }
        String merged = group.toString().replaceAll("\\s+", " ").trim();
        if (!merged.isBlank()) texts.add(merged);

        log.info("[PO-QTY-ARTICLE] Searching in {} text lines", texts.size());

        // Find "Quantity per Article" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("quantity per artic")
                    || low.contains("quanity per artic")
                    || low.contains("qty per article")
                    || (low.contains("quantity") && low.contains("per") && low.contains("artic"))) {
                headerIdx = i;
                log.info("[PO-QTY-ARTICLE] Found header at line {}: {}", i, texts.get(i));
                break;
            }
        }
        if (headerIdx < 0) {
            log.warn("[PO-QTY-ARTICLE] Header 'Quantity per Article' not found");
            return out;
        }

        Pattern costQtyPat = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})\\s+([\\d\\s]+)$");
        Pattern articleStartPat = Pattern.compile("^(\\d{3})\\s+([\\d\\-]+)\\s*(.*)$");
        Pattern hmColourPrefixPat = Pattern.compile("^\\d{2}-\\d{2}$");
        Pattern hmSplitFixPat = Pattern.compile("^(\\d)\\s+(.*)$");
        Pattern hmSplitFixCompactPat = Pattern.compile("^(\\d)(\\d{2})(.*)$");

        String lastColourLine = "";
        String pendingArticleNo = "";
        String pendingHmColourCode = "";
        String pendingMiddle = "";

        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 120); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);

            // Stop at next section
            if (low.contains("total quantity") || low.contains("invoice average")
                    || low.contains("sales sample") || low.contains("time of delivery")
                    || low.contains("terms of delivery")
                    || low.contains("purchase order detail")
                    || low.contains("purchase order")) {
                break;
            }

            if (!line.matches("^\\d{3}\\b.*")
                    && !costQtyPat.matcher(line).find()
                    && !low.contains("article no")
                    && !low.contains("colour code")
                    && !low.contains("qty/article")
                    && !low.contains("cost")) {
                if (!pendingArticleNo.isBlank()) {
                    pendingMiddle = pendingMiddle.isBlank() ? line : (pendingMiddle + " " + line);
                } else {
                    if (!line.matches("^[A-Z]{2}$")) {
                        lastColourLine = lastColourLine.isBlank() ? line : (lastColourLine + " " + line);
                    }
                }
                continue;
            }

            Matcher start = articleStartPat.matcher(line);
            if (start.find()) {
                pendingArticleNo = start.group(1).trim();
                pendingHmColourCode = start.group(2).trim();
                pendingMiddle = start.group(3).trim();
                continue;
            }
            if (!pendingArticleNo.isBlank()) {
                if (hmColourPrefixPat.matcher(pendingHmColourCode).find()) {
                    Matcher hmFix = hmSplitFixPat.matcher(line);
                    if (hmFix.find()) {
                        pendingHmColourCode = pendingHmColourCode + hmFix.group(1);
                        String rest = hmFix.group(2).trim();
                        if (!rest.isBlank()) {
                            pendingMiddle = pendingMiddle.isBlank() ? rest : (pendingMiddle + " " + rest);
                        }
                        continue;
                    }

                    String compact = line.replaceAll("\\s+", "");
                    Matcher hmFix2 = hmSplitFixCompactPat.matcher(compact);
                    if (hmFix2.find()) {
                        pendingHmColourCode = pendingHmColourCode + hmFix2.group(1);
                        String rest = (hmFix2.group(2) + " " + hmFix2.group(3)).trim();
                        if (!rest.isBlank()) {
                            pendingMiddle = pendingMiddle.isBlank() ? rest : (pendingMiddle + " " + rest);
                        }
                        continue;
                    }
                }
            }

            Matcher cq = costQtyPat.matcher(line);
            if (cq.find() && !pendingArticleNo.isBlank()) {
                String cost = cq.group(1).trim() + " " + cq.group(2).trim();
                String qtyArticle = cq.group(3).replaceAll("\\s+", " ").trim();

                String combinedMiddle = pendingMiddle;

                String ptArticleNumber = "";
                String optionNo = "";
                String colour = lastColourLine.trim();

                String middle = combinedMiddle;
                if (middle == null) middle = "";
                String[] parts = middle.trim().isEmpty() ? new String[0] : middle.trim().split("\\s+");
                if (parts.length >= 1) {
                    ptArticleNumber = parts[0];
                    if (parts.length >= 2) {
                        ptArticleNumber = parts[0] + " " + parts[1];
                    }
                }
                if (middle.contains("(")) {
                    int parenIdx = middle.indexOf('(');
                    optionNo = middle.substring(parenIdx).replaceAll("[()]", "").trim();
                } else if (middle.matches(".*\\b[0-9A-Z]{3,}\\b.*")) {
                    Matcher opt = Pattern.compile("\\b([0-9A-Z]{3,})\\b").matcher(middle);
                    if (opt.find()) optionNo = opt.group(1).trim();
                }

                Map<String, String> row = new LinkedHashMap<>();
                row.put("articleNo", pendingArticleNo);
                row.put("hmColourCode", pendingHmColourCode);
                row.put("ptArticleNumber", ptArticleNumber);
                row.put("colour", colour);
                row.put("optionNo", optionNo);
                row.put("cost", cost);
                row.put("qtyArticle", qtyArticle);
                out.add(row);
                log.info("[PO-QTY-ARTICLE] Extracted row: {} | {} | {} | {} | {} | {} | {}",
                        pendingArticleNo, pendingHmColourCode, ptArticleNumber, colour, optionNo, cost, qtyArticle);

                pendingArticleNo = "";
                pendingHmColourCode = "";
                pendingMiddle = "";
                lastColourLine = "";
                continue;
            }

            if (!pendingArticleNo.isBlank()) {
                if (pendingMiddle.isBlank()) pendingMiddle = line;
                else pendingMiddle = pendingMiddle + " " + line;
            }
        }

        log.info("[PO-QTY-ARTICLE] Total rows extracted: {}", out.size());
        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderQuantityPerArticleFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            Pattern articlePrefixPat = Pattern.compile("^(\\d{3})\\s+(\\d{2}-\\d{3})\\s+(\\d{2})\\s+(.*)$");
            Pattern optionPat = Pattern.compile("\\b([0-9A-Z]{3,})\\s*\\((V\\d+)\\)");
            Pattern costQtyPat = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})\\s+([\\d\\s]+)$");

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String low = lines[i] == null ? "" : lines[i].toLowerCase(Locale.ROOT);
                    if (low.contains("quantity per artic")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 200); i++) {
                    String raw = lines[i];
                    if (raw == null) continue;
                    String line = oneLine(raw).trim();
                    if (line.isBlank()) continue;
                    String low = line.toLowerCase(Locale.ROOT);
                    if (low.contains("total quantity") || low.contains("invoice average")
                            || low.contains("sales sample") || low.contains("time of delivery")) {
                        break;
                    }
                    if (low.contains("article no") || low.contains("colour code") || low.contains("qty/article") || low.contains("cost")) {
                        continue;
                    }
                    if (!line.matches("^\\d{3}\\b.*") || !costQtyPat.matcher(line).find()) {
                        continue;
                    }

                    Matcher cq = costQtyPat.matcher(line);
                    if (!cq.find()) continue;

                    String cost = cq.group(1).trim() + " " + cq.group(2).trim();
                    String qtyArticle = cq.group(3).replaceAll("\\s+", " ").trim();
                    String prefix = line.substring(0, cq.start()).replaceAll("\\s+", " ").trim();

                    Matcher prefixM = articlePrefixPat.matcher(prefix);
                    if (!prefixM.find()) continue;

                    String articleNo = prefixM.group(1).trim();
                    String hmColourCode = prefixM.group(2).trim();
                    String ptArticleNumber = prefixM.group(3).trim();
                    String remainder = prefixM.group(4) == null ? "" : prefixM.group(4).trim();

                    String colour = "";
                    String optionNo = "";
                    Matcher optM = optionPat.matcher(remainder);
                    if (optM.find()) {
                        optionNo = (optM.group(1).trim() + " (" + optM.group(2).trim() + ")").trim();
                        colour = remainder.substring(0, optM.start()).replaceAll("\\s+", " ").trim();
                    } else {
                        colour = remainder.replaceAll("\\s+", " ").trim();
                    }

                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("page", String.valueOf(page));
                    row.put("articleNo", articleNo);
                    row.put("hmColourCode", hmColourCode);
                    row.put("ptArticleNumber", ptArticleNumber);
                    row.put("colour", colour);
                    row.put("optionNo", optionNo);
                    row.put("cost", cost);
                    row.put("qtyArticle", qtyArticle);
                    out.add(row);
                }
            }
        } catch (Exception ex) {
            log.warn("[PO-QTY-ARTICLE][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    /**
     * Extract "Invoice Average Price" table from Purchase Order PDF.
     * Columns: invoiceAveragePrice, country
     */
    private static List<Map<String, String>> extractPurchaseOrderInvoiceAvgPrice(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) {
            log.debug("[PO-INVOICE-PRICE] allLines is null or empty");
            return out;
        }

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        log.info("[PO-INVOICE-PRICE] Searching in {} text lines", texts.size());

        // Find "Invoice Average Price" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("invoice average price")) {
                headerIdx = i;
                log.info("[PO-INVOICE-PRICE] Found header at line {}: {}", i, texts.get(i));
                break;
            }
        }
        if (headerIdx < 0) {
            log.warn("[PO-INVOICE-PRICE] Header 'Invoice Average Price' not found");
            return out;
        }

        // Support both formats:
        // - two lines: "113364.36 IDR" then "ID"
        // - one line:  "6.85 USD IN"
        Pattern priceOnlyPat = Pattern.compile("^([\\d\\.,]+)\\s+([A-Z]{2,3})$");
        Pattern countryOnlyPat = Pattern.compile("^([A-Z]{2})$");
        Pattern countryListPat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)+[A-Z]{2}$");
        Pattern priceCountrySameLinePat = Pattern.compile("^([\\d\\.,]+)\\s+([A-Z]{2,3})\\s+([A-Z]{2})$");

        String lastPrice = "";
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 40); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);

            if (low.contains("sales sample") || low.contains("terms of delivery")
                    || low.contains("time of delivery") || low.contains("quantity per artic")) {
                break;
            }

            Matcher sameLine = priceCountrySameLinePat.matcher(line);
            if (sameLine.find()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("invoiceAveragePrice", sameLine.group(1) + " " + sameLine.group(2));
                row.put("country", sameLine.group(3));
                out.add(row);
                log.info("[PO-INVOICE-PRICE] Extracted row: {} | {}", row.get("invoiceAveragePrice"), row.get("country"));
                lastPrice = "";
                continue;
            }

            Matcher priceOnly = priceOnlyPat.matcher(line);
            Matcher countryOnly = countryOnlyPat.matcher(line);
            if (priceOnly.find()) {
                lastPrice = line;
                continue;
            }

            String cleanedCountryLine = line.replaceAll("\\s+", " ").replaceAll(",\\s*$", "").trim();
            boolean isCountry = countryOnlyPat.matcher(cleanedCountryLine).find() || countryListPat.matcher(cleanedCountryLine).find();
            if (isCountry && !lastPrice.isEmpty()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("invoiceAveragePrice", lastPrice);
                row.put("country", cleanedCountryLine);
                out.add(row);
                log.info("[PO-INVOICE-PRICE] Extracted row: {} | {}", lastPrice, cleanedCountryLine);
                lastPrice = "";
            }
        }

        log.info("[PO-INVOICE-PRICE] Total rows extracted: {}", out.size());
        return out;
    }

    private static void annotatePurchaseOrderInvoiceAvgPricePages(List<Map<String, String>> rows, List<OcrNewLine> allLines) {
        if (rows == null || rows.isEmpty()) return;
        if (allLines == null || allLines.isEmpty()) {
            for (Map<String, String> r : rows) {
                if (r != null && !r.containsKey("page")) r.put("page", "1");
            }
            return;
        }

        // Build per-page text for quick contains() checks.
        Map<Integer, StringBuilder> pageText = new LinkedHashMap<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            int p = l.getPage();
            pageText.computeIfAbsent(p, k -> new StringBuilder());
            StringBuilder sb = pageText.get(p);
            if (sb.length() > 0) sb.append("\n");
            sb.append(oneLine(l.getText()));
        }

        for (Map<String, String> r : rows) {
            if (r == null) continue;
            if (r.containsKey("page") && r.get("page") != null && !r.get("page").isBlank()) continue;

            String price = oneLine(r.get("invoiceAveragePrice"));
            String country = oneLine(r.get("country"));
            int chosen = 1;

            for (Map.Entry<Integer, StringBuilder> en : pageText.entrySet()) {
                int p = en.getKey();
                String txt = en.getValue().toString();
                boolean priceHit = price != null && !price.isBlank() && txt.contains(price);
                boolean countryHit = country != null && !country.isBlank() && txt.contains(country);
                if (priceHit || countryHit) {
                    chosen = p;
                    // Prefer a page that contains BOTH, if possible.
                    if (priceHit && countryHit) break;
                }
            }

            r.put("page", String.valueOf(chosen));
        }
    }
}
