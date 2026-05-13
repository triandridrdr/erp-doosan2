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
import java.util.Collections;
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
    /**
     * Match an H&amp;M baby/kids age-range size line. Captures:
     *   group(1) = age range, e.g. "0-1M", "1-2M", "9-12M", "12-18M",
     *              "1½-2Y", "2-3Y", "10-12Y" (the unicode ½ or "1/2" form
     *              both supported)
     *   group(2) = cm size inside parentheses, e.g. "50", "92", "152"
     *   group(3) = quantity (the trailing integer)
     * Examples that match:
     *   "0-1M (50)* 16", "1-2M (56)* 36", "9-12M (80)* 52",
     *   "12-18M (86)* 69", "1½-2Y (92)* 68", "2-3Y (98)* 52".
     * The trailing '*' (or other punctuation) is optional because OCR may
     * drop it and PDFBox sometimes strips it.
     */
    private static final Pattern SIZE_VALUE_LINE_KIDS = Pattern.compile(
            "(?i)^\\s*(\\d{1,2}(?:\u00BD|1/2)?-\\d{1,2}[MY])\\s*\\(\\s*(\\d{2,3})\\s*\\)\\s*[*+.,'\"\u2022]?\\s*(\\d[\\d\\s]{0,12})\\s*$");
    // Matches numeric-only cm sizes: "50 (50)* 35", "86 (86)* 82" used in Italy/Spain, Australia pages
    private static final Pattern SIZE_VALUE_LINE_NUMERIC_CM = Pattern.compile(
            "(?i)^\\s*(\\d{2,3})\\s*\\(\\s*(\\d{2,3})\\s*\\)\\s*[*+.,'\"•]?\\s*(\\d[\\d\\s]{0,12})\\s*$");
    // Universal fallback: any "label (value)* qty" line — fires only when in a valid section.
    // Handles formats like "0M (50)* 2", "1½ (86)* 8", "ONE SIZE (OS)* 100", future unknown formats.
    private static final Pattern SIZE_VALUE_LINE_ANY = Pattern.compile(
            "^\\s*([A-Za-z0-9½¼¾][A-Za-z0-9½¼¾ \\-\\/]{0,19}?)\\s*\\(\\s*([^)]{1,20})\\s*\\)\\s*[*+.,'\"•]?\\s*(\\d[\\d ]{0,12})\\s*$");
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

    private static String inferPurchaseOrderTermsOfDeliveryGlobalBodyFromLines(List<OcrNewLine> allLines) {
        if (allLines == null || allLines.isEmpty()) return "";

        List<String> lines = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String s = oneLine(l.getText()).replaceAll("\\s+", " ").trim();
            if (s.isBlank()) continue;
            lines.add(s);
        }
        if (lines.isEmpty()) return "";

        int bestStart = -1;
        String bestBlock = "";
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            String low = s.toLowerCase(Locale.ROOT);

            boolean isAnchor = low.contains("transport by") || low.contains("incoterms");
            if (!isAnchor) continue;

            StringBuilder block = new StringBuilder();
            int added = 0;
            for (int j = i; j < Math.min(lines.size(), i + 6); j++) {
                String sj = lines.get(j);
                String lowj = sj.toLowerCase(Locale.ROOT);

                boolean looksLikeTerms = lowj.contains("transport by")
                        || lowj.contains("incoterms")
                        || lowj.contains("ship by")
                        || lowj.contains("free carrier")
                        || lowj.contains("service provider information")
                        || lowj.contains("origin delivery information")
                        || lowj.contains("account number")
                        || lowj.contains("account no")
                        || lowj.contains("fca")
                        || lowj.contains("fob");

                if (!looksLikeTerms) {
                    if (added > 0) break;
                    continue;
                }

                if (block.length() > 0) block.append("\n");
                block.append(sj);
                added++;

                if (lowj.startsWith("by accepting") || lowj.contains("supplier acknowledges")) break;
            }

            String cleaned = cleanPurchaseOrderTermsOfDeliveryText(block.toString());
            if (!cleaned.isBlank() && cleaned.length() > bestBlock.length()) {
                bestBlock = cleaned;
                bestStart = i;
            }
        }

        if (bestStart < 0) return "";
        return bestBlock;
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
            // Avoid over-broad triggers like "Invoice Average Price Country" that appear in other sections.
            // This fallback should only activate when the document contains the explicit Country Breakdown section.
            String s2 = s.replaceAll("\\s+", " ");
            if (s2.contains("colour / country breakdown")
                    || s2.contains("color / country breakdown")
                    || s2.contains("colour/country breakdown")
                    || s2.contains("color/country breakdown")
                    || (s2.contains("country") && s2.contains("breakdown") && (s2.contains("colour") || s2.contains("color")))
                    || s2.equals("country breakdown")
                    || s2.endsWith("country breakdown")) {
                startIdx = i;
                break;
            }
        }

        // Store-style Total Country Breakdown may not include the literal phrase "Country Breakdown".
        // It may only show a header line like "Country" followed by one or more "Article:001" lines.
        // Add a conservative secondary anchor: "Country" + nearby "Article:".
        if (startIdx < 0) {
            for (int i = 0; i < normLines.size(); i++) {
                String s = nvl(normLines.get(i)).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                if (!s.equals("country")) continue;
                boolean hasArticleNearby = false;
                for (int j = i; j < Math.min(normLines.size(), i + 10); j++) {
                    String sj = nvl(normLines.get(j)).toLowerCase(Locale.ROOT);
                    if (sj.contains("article:")) {
                        hasArticleNearby = true;
                        break;
                    }
                }
                if (hasArticleNearby) {
                    startIdx = i;
                    break;
                }
            }
        }

        if (startIdx < 0) return out;

        // Try to pick up article columns from the header (e.g., "Article:001 12-220")
        List<String> articleKeys = new ArrayList<>();
        Pattern articlePat = Pattern.compile("(?i)\\barticle\\s*:?")
                ;
        Pattern articleKeyPat = Pattern.compile("(?i)\\barticle\\s*:?\\s*(\\d{3})\\b(?:\\s+(\\d{2}-\\d{3}))?");
        Pattern articleCodeOnlyPat = Pattern.compile("\\b(\\d{2}-\\d{3})\\b");
        // Store layout sometimes splits header across lines:
        // "Article:001" then next line "12-220".
        // Also, the article header block can be longer than 16 lines.
        for (int i = startIdx; i < Math.min(normLines.size(), startIdx + 40); i++) {
            String line = normLines.get(i);
            if (line == null) continue;
            if (!articlePat.matcher(line).find()) continue;
            Matcher m = articleKeyPat.matcher(line);
            while (m.find()) {
                String no = nvl(m.group(1)).trim();
                if (no.isBlank()) continue;
                String code = nvl(m.group(2)).trim();
                if (code.isBlank()) {
                    // Lookahead for the article code on subsequent lines
                    for (int j = i + 1; j < Math.min(normLines.size(), i + 4); j++) {
                        String nx = nvl(normLines.get(j)).trim();
                        if (nx.isBlank()) continue;
                        Matcher cm = articleCodeOnlyPat.matcher(nx);
                        if (cm.find()) {
                            code = nvl(cm.group(1)).trim();
                            break;
                        }
                    }
                }
                String key = "Article:" + no + (code.isBlank() ? "" : (" " + code));
                if (!articleKeys.contains(key)) articleKeys.add(key);
            }
        }

        // Some store PDFs do not OCR all "Article:002" / "Article:003" headers near the country table.
        // However, they usually contain an "Article Total:" block later which lists all articles:
        // "001 12-220 9 554", "002 55-104 9 386", ...
        // Use that as a secondary source of article keys when header detection yields only one article.
        if (articleKeys.size() <= 1) {
            Pattern articleTotalAnchorPat = Pattern.compile("(?i)\\barticle\\s+total\\b");
            Pattern articleTotalRowPat = Pattern.compile("^\\s*(\\d{3})\\s+(\\d{2}-\\d{3})\\b");
            Pattern articleTotalRowAnyPat = Pattern.compile("\\b(\\d{3})\\s+(\\d{2}-\\d{3})\\b");
            int anchor = -1;
            for (int i = startIdx; i < normLines.size(); i++) {
                String s = nvl(normLines.get(i)).replaceAll("\\s+", " ").trim();
                if (s.isBlank()) continue;
                if (articleTotalAnchorPat.matcher(s).find()) {
                    anchor = i;
                    break;
                }
            }
            if (anchor >= 0) {
                // Sometimes the entire block is in a single OCR line with separators (e.g. '|').
                // Parse all occurrences from the anchor line itself.
                {
                    String s0 = nvl(normLines.get(anchor)).replaceAll("\\s+", " ").trim();
                    Matcher mm = articleTotalRowAnyPat.matcher(s0);
                    while (mm.find()) {
                        String no = nvl(mm.group(1)).trim();
                        String code = nvl(mm.group(2)).trim();
                        if (no.isBlank()) continue;
                        String key = "Article:" + no + (code.isBlank() ? "" : (" " + code));
                        if (!articleKeys.contains(key)) articleKeys.add(key);
                    }
                }

                for (int i = anchor + 1; i < Math.min(normLines.size(), anchor + 30); i++) {
                    String s = nvl(normLines.get(i)).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("size label") || low.startsWith("created:") || low.startsWith("page:")) break;

                    Matcher m = articleTotalRowPat.matcher(s);
                    if (m.find()) {
                        String no = nvl(m.group(1)).trim();
                        String code = nvl(m.group(2)).trim();
                        if (!no.isBlank()) {
                            String key = "Article:" + no + (code.isBlank() ? "" : (" " + code));
                            if (!articleKeys.contains(key)) articleKeys.add(key);
                        }
                    }
                }
            }
        }

        // Accept codes like PM-UK, PMUK, OL-UK, OLUK, OLEEU, etc. Code is REQUIRED to avoid false positives.
        Pattern codePat = Pattern.compile("\\b(?:PM|OL)[- ]?[A-Z0-9]{2,6}\\b", Pattern.CASE_INSENSITIVE);
        // Prefer stricter 2-3 letter destination after PM/OL (e.g., OL-IN, PM-UK)
        Pattern codeStrictPat = Pattern.compile("\\b(?:PM|OL)[- ]?[A-Z]{2,3}\\b", Pattern.CASE_INSENSITIVE);
        Pattern numPat = Pattern.compile("(?<![A-Za-z])\\d[\\d\\s.,]*");
        // Country token at start, allow 2-3 uppercase letters (e.g., OE, OL, OLE)
        Pattern country2Pat = Pattern.compile("^([A-Z]{2,3})\\b");

        // Last-resort: infer missing article columns from the country rows themselves.
        // In store layout, OCR sometimes only captures "Article:001" but still provides per-row numbers
        // for Article 002/003 as separate lines (e.g. one line "6 459 2578" followed by "2 496", "1 385").
        // If we detect more numeric groups than (1 + articleKeys.size()), synthesize Article:002.. keys.
        if (articleKeys.size() <= 1) {
            java.util.function.Function<String, List<String>> splitNumberGroups = (raw) -> {
                if (raw == null) return List.of();
                String s = raw.replaceAll("[^0-9]", " ").replaceAll("\\s+", " ").trim();
                if (s.isBlank()) return List.of();
                String[] toks = s.split(" ");
                List<String> groups = new ArrayList<>();
                StringBuilder cur = new StringBuilder();
                for (String t : toks) {
                    if (t == null || t.isBlank()) continue;
                    boolean isThousandChunk = t.length() == 3;
                    boolean groupStarted = cur.length() > 0;
                    if (!groupStarted) {
                        cur.append(t);
                    } else if (isThousandChunk) {
                        cur.append(t);
                    } else {
                        groups.add(cur.toString());
                        cur.setLength(0);
                        cur.append(t);
                    }
                }
                if (cur.length() > 0) groups.add(cur.toString());
                return groups;
            };

            int maxGroups = 0;
            for (int i = startIdx; i < Math.min(normLines.size(), startIdx + 120); i++) {
                String line = nvl(normLines.get(i));
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("bill of material") || low.startsWith("labels") || low.startsWith("production units")) break;
                if (low.startsWith("total:")) break;
                if (low.contains("order no") || low.contains("product no") || low.contains("product name")
                        || low.contains("date of order") || low.contains("supplier code") || low.contains("season:")
                        || low.contains("supplier name") || low.startsWith("created:") || low.startsWith("page:")) {
                    continue;
                }

                // require a PM/OL code similar to row detection
                int searchFrom = 0;
                Matcher c2peek = country2Pat.matcher(line);
                if (c2peek.find()) searchFrom = c2peek.end();
                String suffix = line.substring(Math.min(searchFrom, line.length()));
                boolean hasCode = codeStrictPat.matcher(suffix).find() || codePat.matcher(suffix).find();
                if (!hasCode) continue;

                StringBuilder rawTailBuf2 = new StringBuilder();
                java.util.function.Consumer<String> appendNums = (src) -> {
                    if (src == null || src.isBlank()) return;
                    String numsOnly = src.replaceAll("[^0-9]", " ");
                    if (rawTailBuf2.length() > 0) rawTailBuf2.append(' ');
                    rawTailBuf2.append(numsOnly);
                };

                appendNums.accept(line);
                for (int j = i + 1; j < Math.min(normLines.size(), i + 6); j++) {
                    String next = nvl(normLines.get(j));
                    String nlow = next.toLowerCase(Locale.ROOT);
                    if (nlow.startsWith("total:") || nlow.startsWith("created:") || nlow.startsWith("page:")) break;
                    // stop if looks like new row
                    int sf = 0;
                    Matcher nc2 = country2Pat.matcher(next);
                    if (nc2.find()) sf = nc2.end();
                    String nsuffix = next.substring(Math.min(sf, next.length()));
                    boolean looksNewRow = codeStrictPat.matcher(nsuffix).find() || codePat.matcher(nsuffix).find();
                    if (looksNewRow) break;
                    appendNums.accept(next);
                }

                List<String> groups = splitNumberGroups.apply(rawTailBuf2.toString());
                if (groups.size() > maxGroups) maxGroups = groups.size();
            }

            int desiredArticles = Math.max(0, maxGroups - 1); // first group = total
            int haveArticles = articleKeys == null ? 0 : articleKeys.size();
            if (desiredArticles > haveArticles) {
                for (int ai = haveArticles + 1; ai <= desiredArticles; ai++) {
                    String no = String.format(Locale.ROOT, "%03d", ai);
                    String key = "Article:" + no;
                    if (!articleKeys.contains(key)) articleKeys.add(key);
                }
            }
        }

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

            // Parse quantities. For multi-article docs, we expect: Total + N article columns
            // Example OCR line: "US PM-US 6 459 2 578 2 496 1 385"
            // We treat the numeric tail as (1 + articleCount) numbers.
            int expectedCount = 1 + (articleKeys == null ? 0 : articleKeys.size());
            String total = "";
            Map<String, String> articleVals = new LinkedHashMap<>();

            // Some OCR layouts wrap the numeric tail onto the following lines:
            // e.g. "US PM-US" then "6 459 2 578" then "2 496" then "1 385".
            // Accumulate numeric fragments until we have enough parts (or we hit the next row).
            StringBuilder rawTailBuf = new StringBuilder();
            java.util.function.Consumer<String> appendNumsFromLine = (src) -> {
                if (src == null || src.isBlank()) return;
                Matcher m = numPat.matcher(src);
                while (m.find()) {
                    String g = nvl(m.group()).trim();
                    if (g.isBlank()) continue;
                    if (rawTailBuf.length() > 0) rawTailBuf.append(" ");
                    rawTailBuf.append(g);
                }
            };

            appendNumsFromLine.accept(line);

            List<String> parts = splitMultiArticleNumbers(rawTailBuf.toString(), expectedCount);
            if (parts.size() < expectedCount) {
                for (int j = i + 1; j < Math.min(normLines.size(), i + 8); j++) {
                    String nextLine = nvl(normLines.get(j));
                    String nextLow = nextLine.toLowerCase(Locale.ROOT);
                    if (nextLow.startsWith("bill of material") || nextLow.startsWith("labels") || nextLow.startsWith("production units")) {
                        break;
                    }
                    if (nextLow.contains("order no") || nextLow.contains("product no") || nextLow.contains("product name")
                            || nextLow.contains("date of order") || nextLow.contains("supplier code") || nextLow.contains("season:")
                            || nextLow.contains("supplier name") || nextLow.startsWith("created:") || nextLow.startsWith("page:")) {
                        continue;
                    }

                    // Stop accumulating when the next row starts (another country + PM/OL code).
                    int nextSearchFrom = 0;
                    Matcher nc2 = country2Pat.matcher(nextLine);
                    if (nc2.find()) nextSearchFrom = nc2.end();
                    String nextSuffix = nextLine.substring(Math.min(nextSearchFrom, nextLine.length()));
                    boolean nextLooksLikeNewRow = codeStrictPat.matcher(nextSuffix).find() || codePat.matcher(nextSuffix).find();
                    if (nextLooksLikeNewRow) break;

                    appendNumsFromLine.accept(nextLine);
                    parts = splitMultiArticleNumbers(rawTailBuf.toString(), expectedCount);
                    if (parts.size() >= expectedCount) {
                        // We consumed numeric continuation lines for this row; skip them in the outer loop.
                        i = j;
                        break;
                    }
                }
            }

            if (parts.isEmpty()) continue;

            if (articleKeys != null && !articleKeys.isEmpty() && parts.size() >= expectedCount) {
                total = nvl(parts.get(0)).trim();
                for (int ai = 0; ai < articleKeys.size(); ai++) {
                    String k = articleKeys.get(ai);
                    String v = nvl(parts.get(ai + 1)).trim();
                    articleVals.put(k, v);
                }
            } else {
                // Fallback (single total column only): keep prior behavior of choosing last number
                List<String> nums = new ArrayList<>();
                Matcher numM2 = numPat.matcher(line);
                while (numM2.find()) {
                    String v = numM2.group().trim();
                    if (!v.isBlank()) nums.add(v);
                }
                if (nums.isEmpty()) continue;
                String last = nums.get(nums.size() - 1);
                String penult = nums.size() >= 2 ? nums.get(nums.size() - 2) : null;
                String lastN = normalizeNumberToken(last);
                String penN = penult != null ? normalizeNumberToken(penult) : null;
                String chosen = (penN != null && penN.equals(lastN)) ? penult : last;
                total = normalizeNumberToken(chosen);
            }

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
            if (articleVals != null && !articleVals.isEmpty()) {
                for (Map.Entry<String, String> en : articleVals.entrySet()) {
                    if (en == null) continue;
                    m.put(en.getKey(), en.getValue());
                }
            }

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

    private static Map<String, String> buildArticleColourNameByNoFromLines(List<OcrNewLine> allLines) {
        Map<String, String> out = new LinkedHashMap<>();
        if (allLines == null || allLines.isEmpty()) return out;

        List<String> articleNos = new ArrayList<>();
        String colourJoined = "";

        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");
        Pattern stopPat = Pattern.compile("(?i)^(description|pt\\s*article|option\\s*no|cost|qty|quantity|assortment)\\b");

        boolean inColour = false;
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String raw = oneLine(l.getText());
            String t = nvl(raw).trim();
            if (t.isBlank()) continue;

            String low = t.toLowerCase(Locale.ROOT);

            if (articleNos.isEmpty() && low.contains("article") && low.contains("no")) {
                Matcher m = aPat.matcher(t);
                while (m.find()) articleNos.add(m.group(1));
                continue;
            }

            if (!inColour && low.contains("colour") && low.contains("name")) {
                inColour = true;
                int at = t.indexOf(':');
                String after = at >= 0 ? t.substring(at + 1) : t;
                colourJoined = (colourJoined + " " + after).replaceAll("\\s+", " ").trim();
                continue;
            }

            if (inColour) {
                if (stopPat.matcher(t).find()) break;
                if (low.contains("article") && low.contains("no")) break;
                if (low.contains("h&m") && low.contains("code")) break;
                colourJoined = (colourJoined + " " + t).replaceAll("\\s+", " ").trim();
            }
        }

        if (articleNos.isEmpty()) return out;
        String v = nvl(colourJoined).replaceAll("\\s*\\|\\s*", " ").replaceAll("\\s+", " ").trim();
        if (v.isBlank()) return out;

        if (articleNos.size() == 1) {
            out.putIfAbsent(articleNos.get(0), v);
        }
        return out;
    }

    private static Map<String, String> buildArticleColourNameByNoFromTables(List<OcrNewTableDto> tables) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tables == null || tables.isEmpty()) return out;

        List<String> articleNos = new ArrayList<>();
        List<String> colourNames = new ArrayList<>();
        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");

        for (OcrNewTableDto t : tables) {
            if (t == null || t.getRows() == null) continue;
            for (List<String> row : t.getRows()) {
                if (row == null || row.isEmpty()) continue;
                String c0 = nvl(row.get(0)).trim();
                if (c0.isBlank()) continue;
                String low0 = c0.toLowerCase(Locale.ROOT);

                if (articleNos.isEmpty() && low0.contains("article") && low0.contains("no")) {
                    for (int i = 1; i < row.size(); i++) {
                        String cell = nvl(row.get(i)).trim();
                        Matcher m = aPat.matcher(cell);
                        if (m.find()) articleNos.add(m.group(1));
                    }
                    continue;
                }

                if (colourNames.isEmpty() && low0.contains("colour") && low0.contains("name")) {
                    for (int i = 1; i < row.size(); i++) {
                        String cell = nvl(row.get(i)).trim();
                        if (!cell.isBlank()) colourNames.add(cell);
                    }
                }

                if (!articleNos.isEmpty() && !colourNames.isEmpty()) break;
            }
            if (!articleNos.isEmpty() && !colourNames.isEmpty()) break;
        }

        if (articleNos.isEmpty() || colourNames.isEmpty()) return out;

        // OCR table extraction may split a single colour name across multiple cells.
        // Try to merge cells so we end up with exactly one colour name per article.
        int expected = articleNos.size();
        List<String> merged = new ArrayList<>();
        if (colourNames.size() == expected) {
            merged.addAll(colourNames);
        } else {
            int i = 0;
            while (i < colourNames.size() && merged.size() < expected) {
                int remainingCells = colourNames.size() - i;
                int remainingSlots = expected - merged.size();
                String cur = nvl(colourNames.get(i)).trim();
                if (cur.isBlank()) {
                    i++;
                    continue;
                }

                // If we have more cells than slots, try to merge obvious short fragments (e.g. "Light").
                if (remainingCells > remainingSlots && i + 1 < colourNames.size()) {
                    String next = nvl(colourNames.get(i + 1)).trim();
                    boolean nextLooksFragment = !next.isBlank() && next.length() <= 8 && !next.contains(" ");
                    if (nextLooksFragment) {
                        cur = (cur + " " + next).replaceAll("\\s+", " ").trim();
                        i += 2;
                        merged.add(cur);
                        continue;
                    }
                }

                merged.add(cur);
                i++;
            }
        }

        // Fix overlap case where a cell contains the previous colour + a repeated suffix colour.
        // Example (expected 3):
        //   ["White Dusty Light", "Pink Medium", "Dusty White Dusty Light"]
        // -> ["White Dusty Light", "Pink Medium Dusty", "White Dusty Light"]
        if (merged.size() == expected && expected >= 2) {
            for (int j = 0; j < merged.size(); j++) {
                String base = nvl(merged.get(j)).trim();
                if (base.isBlank()) continue;
                for (int k = 0; k < merged.size(); k++) {
                    if (k == j) continue;
                    String cand = nvl(merged.get(k)).trim();
                    if (cand.length() <= base.length()) continue;
                    if (cand.endsWith(base)) {
                        String prefix = cand.substring(0, cand.length() - base.length()).trim();
                        if (!prefix.isBlank() && k - 1 >= 0) {
                            String prev = nvl(merged.get(k - 1)).trim();
                            merged.set(k - 1, (prev + " " + prefix).replaceAll("\\s+", " ").trim());
                            merged.set(k, base);
                        }
                    }
                }
            }
        }

        int n = Math.min(articleNos.size(), merged.size());
        for (int idx = 0; idx < n; idx++) {
            String a3 = nvl(articleNos.get(idx)).trim();
            String name = nvl(merged.get(idx)).trim();
            if (a3.isBlank() || name.isBlank()) continue;
            out.putIfAbsent(a3, name);
        }
        return out;
    }

    private static Map<String, String> buildArticleLabelByNoFromHeader(Map<String, String> formFields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (formFields == null || formFields.isEmpty()) return out;

        String articleNo = "";
        String hmColourCode = "";
        for (Map.Entry<String, String> en : formFields.entrySet()) {
            String k = nvl(en.getKey()).trim().toLowerCase(Locale.ROOT);
            String v = nvl(en.getValue()).trim();
            if (v.isBlank()) continue;
            if (articleNo.isBlank() && (k.equals("article no") || k.equals("article") || k.equals("article / product no") || k.equals("article / product") || k.equals("product no"))) {
                articleNo = v;
            }
            if (hmColourCode.isBlank() && (k.equals("h&m colour code") || k.equals("h&m colour") || k.equals("h&m color code") || k.equals("h&m color"))) {
                hmColourCode = v;
            }
        }

        Matcher am = Pattern.compile("\\b(\\d{3})\\b").matcher(articleNo);
        Matcher cm = Pattern.compile("\\b(\\d{2}-\\d{3})\\b").matcher(hmColourCode);
        String a3 = am.find() ? am.group(1) : "";
        String c23 = cm.find() ? cm.group(1) : "";
        if (a3.isBlank() || c23.isBlank()) return out;

        out.put(a3, (a3 + " " + c23).trim());
        return out;
    }

    private static Map<String, String> buildArticleLabelByNoFromHeaderOrLines(
            Map<String, String> formFields,
            List<OcrNewLine> allLines
    ) {
        Map<String, String> out = buildArticleLabelByNoFromHeader(formFields);
        if (!out.isEmpty()) return out;

        return buildArticleLabelByNoFromLines(allLines);
    }

    private static Map<String, String> buildArticleLabelByNoFromTables(List<OcrNewTableDto> tables) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tables == null || tables.isEmpty()) return out;

        List<String> articleNos = new ArrayList<>();
        List<String> colourCodes = new ArrayList<>();

        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");
        Pattern cPat = Pattern.compile("\\b(\\d{2}-\\d{3})\\b");

        for (OcrNewTableDto t : tables) {
            if (t == null || t.getRows() == null) continue;
            for (List<String> row : t.getRows()) {
                if (row == null || row.isEmpty()) continue;
                String c0 = nvl(row.get(0)).trim();
                if (c0.isBlank()) continue;
                String low0 = c0.toLowerCase(Locale.ROOT);

                if (articleNos.isEmpty() && low0.contains("article") && low0.contains("no")) {
                    for (int i = 1; i < row.size(); i++) {
                        String cell = nvl(row.get(i)).trim();
                        Matcher m = aPat.matcher(cell);
                        if (m.find()) articleNos.add(m.group(1));
                    }
                    continue;
                }

                if (colourCodes.isEmpty() && low0.contains("colour") && low0.contains("code")) {
                    for (int i = 1; i < row.size(); i++) {
                        String cell = nvl(row.get(i)).trim();
                        Matcher m = cPat.matcher(cell);
                        if (m.find()) colourCodes.add(m.group(1));
                    }
                }

                if (!articleNos.isEmpty() && !colourCodes.isEmpty()) break;
            }
            if (!articleNos.isEmpty() && !colourCodes.isEmpty()) break;
        }

        if (articleNos.isEmpty() || colourCodes.isEmpty()) return out;
        int n = Math.min(articleNos.size(), colourCodes.size());
        for (int i = 0; i < n; i++) {
            String a3 = nvl(articleNos.get(i)).trim();
            String c23 = nvl(colourCodes.get(i)).trim();
            if (a3.isBlank() || c23.isBlank()) continue;
            out.putIfAbsent(a3, (a3 + " " + c23).trim());
        }
        return out;
    }

    private static Map<String, String> buildArticleLabelByNoFromLines(List<OcrNewLine> allLines) {
        Map<String, String> out = new LinkedHashMap<>();
        if (allLines == null || allLines.isEmpty()) return out;

        Pattern aPat = Pattern.compile("(?i)\\barticle\\s*no\\s*[:#-]?\\s*(\\d{3})\\b");
        Pattern cPat = Pattern.compile("(?i)h\\s*&\\s*m\\s*colou?r\\s*code\\s*[:#-]?\\s*(\\d{2}-\\d{3})\\b");
        Pattern pairPat = Pattern.compile("\\b(\\d{3})\\b\\s+(\\d{2}-\\d{3})\\b");

        String pendingA3 = "";
        List<String> headerArticleNos = new ArrayList<>();
        List<String> headerColourCodes = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;

            // Fast-path: many OCR layouts include a row like "001 12-220 02" (no explicit labels).
            // Capture all pairs from such lines.
            Matcher pm = pairPat.matcher(t);
            while (pm.find()) {
                String a3p = nvl(pm.group(1)).trim();
                String c23p = nvl(pm.group(2)).trim();
                if (!a3p.isBlank() && !c23p.isBlank()) {
                    out.putIfAbsent(a3p, (a3p + " " + c23p).trim());
                }
            }

            String low = t.toLowerCase(Locale.ROOT);
            if (headerArticleNos.isEmpty() && low.contains("article no")) {
                Matcher m = Pattern.compile("\\b(\\d{3})\\b").matcher(t);
                while (m.find()) headerArticleNos.add(m.group(1));
            }
            if (headerColourCodes.isEmpty() && (low.contains("h&m") || low.contains("h & m")) && low.contains("colou") && low.contains("code")) {
                Matcher m = Pattern.compile("\\b(\\d{2}-\\d{3})\\b").matcher(t);
                while (m.find()) headerColourCodes.add(m.group(1));
            }

            Matcher am = aPat.matcher(t);
            Matcher cm = cPat.matcher(t);

            String a3 = am.find() ? am.group(1) : "";
            String c23 = cm.find() ? cm.group(1) : "";

            if (!a3.isBlank() && !c23.isBlank()) {
                out.putIfAbsent(a3, (a3 + " " + c23).trim());
                pendingA3 = "";
                continue;
            }

            if (!a3.isBlank()) {
                pendingA3 = a3;
                continue;
            }

            if (!c23.isBlank() && !pendingA3.isBlank()) {
                String aa = pendingA3;
                out.putIfAbsent(aa, (aa + " " + c23).trim());
                pendingA3 = "";
            }
        }

        // Another common layout is a 2-row header:
        //   "Article No: | 001 | 002 | 003"
        //   "H&M Colour Code: | 12-220 | 55-104 | 12-220"
        // Zip by column index.
        if (!headerArticleNos.isEmpty() && !headerColourCodes.isEmpty()) {
            int n = Math.min(headerArticleNos.size(), headerColourCodes.size());
            for (int i = 0; i < n; i++) {
                String a3 = nvl(headerArticleNos.get(i)).trim();
                String c23 = nvl(headerColourCodes.get(i)).trim();
                if (a3.isBlank() || c23.isBlank()) continue;
                out.putIfAbsent(a3, (a3 + " " + c23).trim());
            }
        }

        if (out.isEmpty()) return out;
        return out;
    }

    private static void fillColourSizeBreakdownArticleLabelsFromHeaderOrLines(
            List<Map<String, String>> colourSizeBreakdown,
            Map<String, String> formFields,
            List<OcrNewLine> allLines
    ) {
        if (colourSizeBreakdown == null || colourSizeBreakdown.isEmpty()) return;
        Map<String, String> map = buildArticleLabelByNoFromHeaderOrLines(formFields, allLines);
        if (map.isEmpty()) return;

        for (Map<String, String> r : colourSizeBreakdown) {
            if (r == null) continue;
            String a = nvl(r.get("article")).trim();
            if (a.isBlank()) continue;

            Matcher m = Pattern.compile("\\b(\\d{3})\\b").matcher(a);
            if (!m.find()) continue;
            String a3 = m.group(1);
            String label = nvl(map.get(a3)).trim();
            if (label.isBlank()) continue;

            if (a.matches("^\\d{3}$") || a.matches("(?i)^article\\s*[:#-]?\\s*\\d{3}$")) {
                r.put("article", label);
            }
        }
    }

    private static void fillDetailRowColourFieldsFromArticleMaps(
            List<Map<String, String>> detailRows,
            Map<String, String> hmColourCodeByArticleNo,
            Map<String, String> colourNameByArticleNo
    ) {
        if (detailRows == null || detailRows.isEmpty()) return;
        if ((hmColourCodeByArticleNo == null || hmColourCodeByArticleNo.isEmpty())
                && (colourNameByArticleNo == null || colourNameByArticleNo.isEmpty())) return;

        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");
        for (Map<String, String> r : detailRows) {
            if (r == null) continue;
            String a0 = nvl(r.get("articleNo")).trim();
            if (a0.isBlank()) continue;
            Matcher m = aPat.matcher(a0);
            if (!m.find()) continue;
            String a3 = m.group(1);

            if (hmColourCodeByArticleNo != null && !hmColourCodeByArticleNo.isEmpty()) {
                String cur = nvl(r.get("hmColourCode")).trim();
                if (cur.isBlank()) {
                    String v = nvl(hmColourCodeByArticleNo.get(a3)).trim();
                    if (!v.isBlank()) r.put("hmColourCode", v);
                }
            }
            if (colourNameByArticleNo != null && !colourNameByArticleNo.isEmpty()) {
                String v0 = nvl(colourNameByArticleNo.get(a3)).trim();
                String v = v0.replaceAll("\\s*\\|\\s*", " ").replaceAll("\\s+", " ").trim();
                if (!v.isBlank()) {
                    String curUk = nvl(r.get("colour")).trim();
                    if (!curUk.equalsIgnoreCase(v)) {
                        r.put("colour", v);
                    }
                    // Frontend commonly binds to "color".
                    String curUs = nvl(r.get("color")).trim();
                    if (!curUs.equalsIgnoreCase(v)) {
                        r.put("color", v);
                    }
                }
            }
        }
    }

    private static List<Map<String, String>> deriveTotalCountryBreakdownFromSalesOrderDetail(
            List<Map<String, String>> salesOrderDetailSizeBreakdown,
            List<Map<String, String>> purchaseOrderQuantityPerArticle
    ) {
        List<Map<String, String>> out = new ArrayList<>();
        if (salesOrderDetailSizeBreakdown == null || salesOrderDetailSizeBreakdown.isEmpty()) return out;

        // Some OCR layouts merge multi-article totals into a single token like:
        // "1 640 1 592 925" (per-article values separated only by spaces).
        // When normalizeNumberToken() is applied directly, it becomes "16401592925".
        // Split such sequences into number groups and choose the group matching the current article.
        java.util.function.Function<String, List<String>> splitNumberGroups = (raw) -> {
            if (raw == null) return List.of();
            String s = raw.replaceAll("[^0-9]", " ").replaceAll("\\s+", " ").trim();
            if (s.isBlank()) return List.of();
            String[] toks = s.split(" ");
            List<String> groups = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String t : toks) {
                if (t == null || t.isBlank()) continue;
                // Continue a group when the next token looks like a thousands chunk (3 digits)
                // and the group already started (e.g. 1 + 640).
                boolean isThousandChunk = t.length() == 3;
                boolean groupStarted = cur.length() > 0;
                if (!groupStarted) {
                    cur.append(t);
                } else if (isThousandChunk) {
                    cur.append(t);
                } else {
                    groups.add(cur.toString());
                    cur.setLength(0);
                    cur.append(t);
                }
            }
            if (cur.length() > 0) groups.add(cur.toString());
            return groups;
        };

        Map<String, String> articleToCode = new LinkedHashMap<>();
        if (purchaseOrderQuantityPerArticle != null) {
            for (Map<String, String> r : purchaseOrderQuantityPerArticle) {
                if (r == null) continue;
                String a = nvl(r.get("articleNo")).trim();
                String c = nvl(r.get("hmColourCode")).trim();
                if (a.isBlank()) continue;
                if (!articleToCode.containsKey(a)) articleToCode.put(a, c);
            }
        }

        Pattern pmPat = Pattern.compile("(?i)\\b(?:PM|OL)[- ]?[A-Z]{2,3}\\b");
        Pattern countryPat = Pattern.compile("\\b([A-Z]{2,3})\\b\\s*\\(\\s*(?i:(?:PM|OL)[- ]?[A-Z]{2,3})\\b");

        class Agg {
            String country = "";
            String pmCode = "";
            Map<String, Long> byArticle = new LinkedHashMap<>();
            long total = 0L;
        }

        Map<String, Agg> byPm = new LinkedHashMap<>();
        Map<String, List<String>> articleOrderByPm = new LinkedHashMap<>();

        for (Map<String, String> row : salesOrderDetailSizeBreakdown) {
            if (row == null) continue;
            String type = nvl(row.get("type")).trim();
            if (!type.equalsIgnoreCase("total")) continue;

            String dest = nvl(row.get("destinationCountry")).trim();
            if (dest.isBlank()) dest = nvl(row.get("countryOfDestination")).trim();
            if (dest.isBlank()) dest = nvl(row.get("countryOfDestinationText")).trim();

            String pmCode = "";
            Matcher pmM = pmPat.matcher(dest);
            if (pmM.find()) {
                pmCode = pmM.group().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
                if (!pmCode.contains("-") && pmCode.length() >= 4) {
                    pmCode = pmCode.substring(0, 2) + "-" + pmCode.substring(2);
                }
            }
            if (pmCode.isBlank()) {
                pmCode = nvl(row.get("pmCode")).trim();
            }
            if (pmCode.isBlank()) continue;

            String country = "";
            Matcher cM = countryPat.matcher(dest);
            if (cM.find()) {
                country = nvl(cM.group(1)).trim();
            }
            if (country.isBlank()) {
                country = nvl(row.get("country")).trim();
            }
            if (country.isBlank()) {
                // Fallback: use suffix of PM code (PM-US -> US)
                country = pmCode.replaceFirst("(?i)^(PM|OL)-?", "");
            }

            String articleNo = nvl(row.get("articleNo")).trim();
            if (!articleNo.isBlank()) {
                List<String> ord = articleOrderByPm.computeIfAbsent(pmCode, k -> new ArrayList<>());
                if (!ord.contains(articleNo)) ord.add(articleNo);
            }

            long qty = 0L;
            String rawTotal = nvl(row.get("total")).trim();
            String chosenTotal = rawTotal;
            List<String> groups = splitNumberGroups.apply(rawTotal);
            if (!articleNo.isBlank() && groups.size() > 1) {
                // If articleNo is a simple sequence like 001/002/003, map directly to group index.
                // This is more reliable than using a partially-built article order while iterating.
                String artDigits = articleNo.replaceAll("[^0-9]", "");
                if (!artDigits.isBlank()) {
                    try {
                        int artIdx1 = Integer.parseInt(artDigits);
                        if (artIdx1 >= 1 && artIdx1 <= groups.size() && groups.size() <= 10) {
                            chosenTotal = groups.get(artIdx1 - 1);
                        }
                    } catch (Exception ignored) {
                    }
                }

                // Otherwise: try to align groups with observed per-PM article order.
                List<String> ord = articleOrderByPm.getOrDefault(pmCode, List.of());
                int idx = ord.indexOf(articleNo);
                if (idx >= 0 && !ord.isEmpty()) {
                    if (groups.size() == ord.size()) {
                        chosenTotal = groups.get(idx);
                    } else if (groups.size() > ord.size()) {
                        List<String> tail = groups.subList(groups.size() - ord.size(), groups.size());
                        if (idx < tail.size()) chosenTotal = tail.get(idx);
                    } else {
                        // groups < ord.size(): pick the first group to avoid concatenation
                        chosenTotal = groups.get(0);
                    }
                } else {
                    // No index match: pick the last group (often the smallest)
                    chosenTotal = groups.get(groups.size() - 1);
                }
            }
            String normTotal = normalizeNumberToken(chosenTotal);
            if (!normTotal.isBlank()) {
                try {
                    qty = Long.parseLong(normTotal);
                } catch (Exception ignored) {
                }
            }
            if (qty <= 0) {
                // Fallback: sum size keys
                long sum = 0L;
                for (String k : List.of("XS", "S", "M", "L", "XL")) {
                    String v = normalizeNumberToken(nvl(row.get(k)).trim());
                    if (v.isBlank()) continue;
                    try {
                        sum += Long.parseLong(v);
                    } catch (Exception ignored) {
                    }
                }
                qty = sum;
            }

            Agg agg = byPm.computeIfAbsent(pmCode, k -> new Agg());
            if (agg.pmCode.isBlank()) agg.pmCode = pmCode;
            if (agg.country.isBlank()) agg.country = country;

            agg.total += Math.max(0L, qty);
            if (!articleNo.isBlank()) {
                agg.byArticle.put(articleNo, agg.byArticle.getOrDefault(articleNo, 0L) + Math.max(0L, qty));
            }
        }

        if (byPm.isEmpty()) return out;

        // Determine article order from QPA first, then from aggregated keys
        List<String> articleOrder = new ArrayList<>(articleToCode.keySet());
        for (Agg a : byPm.values()) {
            for (String art : a.byArticle.keySet()) {
                if (!articleOrder.contains(art)) articleOrder.add(art);
            }
        }

        for (Agg a : byPm.values()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("country", nvl(a.country));
            m.put("pmCode", nvl(a.pmCode));
            m.put("total", a.total <= 0 ? "" : Long.toString(a.total));
            for (String art : articleOrder) {
                String code = nvl(articleToCode.get(art)).trim();
                String key = "Article:" + art + (code.isBlank() ? "" : (" " + code));
                Long v = a.byArticle.get(art);
                m.put(key, v == null || v <= 0 ? "" : Long.toString(v));
            }
            out.add(m);
        }

        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderTimeOfDeliveryFromPdfBytes(byte[] pdfBytes, String fileName) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

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

            Pattern qtyPercentPat = Pattern.compile("(\\d[\\d\\s]{0,15})\\s+(\\d+%|<\\d+%)\\s*$");

            final String[] currentDate = new String[]{null};
            final StringBuilder planBuf = new StringBuilder();
            final String[] quantity = new String[]{""};
            final String[] percentTotalQty = new String[]{""};

            final Runnable flush = () -> {
                if (currentDate[0] == null) return;
                String planning = planBuf.toString()
                        .replaceAll("\\s+", " ")
                        .replaceAll("\\s*,\\s*", ", ")
                        .trim();
                if (!planning.isBlank() || (!quantity[0].isBlank() && !percentTotalQty[0].isBlank())) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("timeOfDelivery", currentDate[0]);
                    row.put("planningMarkets", planning);
                    row.put("quantity", nvl(quantity[0]).trim());
                    row.put("percentTotalQty", nvl(percentTotalQty[0]).trim());
                    row.put("_src", "pdfText");
                    out.add(row);
                }
                currentDate[0] = null;
                planBuf.setLength(0);
                quantity[0] = "";
                percentTotalQty[0] = "";
            };

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
                boolean hasDate = dm.find() && dm.start() == 0;
                if (hasDate) {
                    // Finish previous row if any
                    flush.run();

                    currentDate[0] = dm.group(0).trim();
                    planBuf.setLength(0);
                    quantity[0] = "";
                    percentTotalQty[0] = "";

                    String tail = s.substring(dm.end()).trim();
                    if (!tail.isBlank()) {
                        Matcher mqp = qtyPercentPat.matcher(tail);
                        if (mqp.find()) {
                            String planPart = tail.substring(0, mqp.start()).trim();
                            if (!planPart.isBlank()) {
                                planBuf.append(planPart);
                            }
                            quantity[0] = mqp.group(1).replaceAll("\\s+", " ").trim();
                            percentTotalQty[0] = mqp.group(2).trim();
                            // Row fully contained in one line
                            flush.run();
                        } else {
                            planBuf.append(tail);
                        }
                    }
                    continue;
                }

                if (currentDate[0] == null) continue;
                if (low.contains("planning markets") || low.equals("quantity") || low.contains("% total")) continue;

                Matcher mqp = qtyPercentPat.matcher(s);
                if (mqp.find()) {
                    String planPart = s.substring(0, mqp.start()).trim();
                    if (!planPart.isBlank()) {
                        if (planBuf.length() > 0) planBuf.append(" ");
                        planBuf.append(planPart);
                    }
                    if (quantity[0].isBlank()) {
                        quantity[0] = mqp.group(1).replaceAll("\\s+", " ").trim();
                    }
                    if (percentTotalQty[0].isBlank()) {
                        percentTotalQty[0] = mqp.group(2).trim();
                    }
                    flush.run();
                    continue;
                }

                if (planBuf.length() > 0) planBuf.append(" ");
                planBuf.append(s);
            }

            flush.run();

            if (!out.isEmpty()) {
                log.info("[PO-TIME-DELIVERY][PDFBOX] file={} extractedRows={}", fileName, out.size());
            }
        } catch (Exception ex) {
            log.warn("[PO-TIME-DELIVERY][PDFBOX] file={} failed: {}", fileName, ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleTermsByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.equals("sales sample terms") || low.startsWith("sales sample terms")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                StringBuilder sb = new StringBuilder();
                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 120); i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("time of delivery")) break;
                    if (low.startsWith("article no")) break;
                    if (low.startsWith("destination studio address")) break;
                    if (low.startsWith("destination")) break;
                    if (low.startsWith("terms of delivery")) break;
                    if (low.startsWith("by accepting")) break;
                    if (low.startsWith("created:")) break;
                    if (low.startsWith("page:")) break;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(s);
                }

                String terms = sb.toString().trim();
                if (terms.isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("salesSampleTerms", terms);
                row.put("_src", "pdfText");
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("[SALES-SAMPLE-TERMS][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleTermsOfDeliveryByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                boolean includeHeaderLine = false;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);

                    // Prefer explicit header, but only if it looks like the Sales Sample courier block.
                    if (low.equals("terms of delivery") || low.startsWith("terms of delivery")) {
                        boolean courierNearby = false;
                        for (int j = i + 1; j < Math.min(lines.length, i + 8); j++) {
                            String t = lines[j] == null ? "" : oneLine(lines[j]).replaceAll("\\s+", " ").trim();
                            String tl = t.toLowerCase(Locale.ROOT);
                            if (tl.contains("courier") || tl.contains("account number") || tl.contains("shipment")) {
                                courierNearby = true;
                                break;
                            }
                        }
                        if (courierNearby) {
                            headerIdx = i;
                            includeHeaderLine = false;
                            break;
                        }
                        continue;
                    }

                    // Fallback: in some PDFs the header isn't present in text layer; detect by content.
                    if (low.startsWith("transport by courier")
                            || low.contains("transport by courier")
                            || low.startsWith("account number to be used")
                            || low.contains("account number to be used")) {
                        headerIdx = i;
                        includeHeaderLine = true;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                StringBuilder sb = new StringBuilder();
                int startIdx = includeHeaderLine ? headerIdx : headerIdx + 1;
                for (int i = startIdx; i < Math.min(lines.length, headerIdx + 60); i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("destination studio address")) break;
                    if (low.startsWith("destination")) break;
                    if (low.startsWith("sales sample terms")) break;
                    if (low.startsWith("time of delivery")) break;
                    if (low.startsWith("article no")) break;
                    if (low.startsWith("by accepting")) break;
                    if (low.startsWith("created:")) break;
                    if (low.startsWith("page:")) break;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(s);
                }

                String tod = sb.toString().trim();
                if (tod.isBlank()) continue;

                // Guard: must look like courier block, otherwise it's likely Purchase Order Terms of Delivery.
                String lowTod = tod.toLowerCase(Locale.ROOT);
                if (!(lowTod.contains("courier") || lowTod.contains("account number") || lowTod.contains("shipment"))) {
                    continue;
                }

                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("termsOfDelivery", tod);
                row.put("_src", "pdfText");
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("[SALES-SAMPLE-TERMS-DELIVERY][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleDestinationStudioAddressByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.equals("destination studio address")
                            || low.startsWith("destination studio address")
                            || low.equals("destination")
                            || low.startsWith("destination")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                StringBuilder sb = new StringBuilder();
                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 40); i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("terms of delivery")) break;
                    if (low.startsWith("sales sample terms")) break;
                    if (low.startsWith("time of delivery")) break;
                    if (low.startsWith("article no")) break;
                    if (low.startsWith("by accepting")) break;
                    if (low.startsWith("created:")) break;
                    if (low.startsWith("page:")) break;
                    s = s.replaceAll("(?i)\\bH&M\\b", "H & M");
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(s);
                }

                String addr = sb.toString().trim();
                if (addr.isBlank()) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", String.valueOf(page));
                row.put("destinationStudioAddress", addr);
                row.put("_src", "pdfText");
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("[SALES-SAMPLE-DEST-STUDIO-ADDR][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleTimeOfDeliveryByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;
                String lowAll = text.toLowerCase(Locale.ROOT);
                if (!lowAll.contains("sales sample") && !lowAll.contains("sales samples")) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.equals("time of delivery") || low.startsWith("time of delivery")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                StringBuilder sb = new StringBuilder();
                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 30); i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("article no")) break;
                    if (low.startsWith("by accepting")) break;
                    if (low.startsWith("created:")) break;
                    if (low.startsWith("page:")) break;
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
                row.put("_src", "pdfText");
                out.add(row);
            }
        } catch (Exception ex) {
            log.warn("[SALES-SAMPLE-TOD][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleArticlesByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        Pattern datePat = Pattern.compile("\\b\\d{1,2}\\s+[A-Za-z]{3},\\s*\\d{4}\\b");

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;
                String lowAll = text.toLowerCase(Locale.ROOT);
                if (!lowAll.contains("sales sample") && !lowAll.contains("sales samples")) continue;
                if (!lowAll.contains("article no")) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.startsWith("article no") && low.contains("tod") && (low.contains("qty") || low.contains("quantity"))) {
                        headerIdx = i;
                        break;
                    }
                }
                int start = headerIdx >= 0 ? headerIdx + 1 : 0;

                boolean extractedAny = false;
                for (int i = start; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
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
                    String post = s.substring(dm.end()).trim();
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
                    if (!post.isBlank()) {
                        String[] postToks = post.split("\\s+");
                        if (postToks.length > 0 && postToks[0] != null) {
                            String cand = postToks[0].trim();
                            if (!(cand.length() <= 24 && cand.matches("[A-Za-z]+"))) {
                                cand = "";
                            }
                            destinationStudio = cand;
                        }
                    }

                    if (destinationStudio.isBlank() && i + 1 < lines.length) {
                        String next = lines[i + 1] == null ? "" : oneLine(lines[i + 1]).replaceAll("\\s+", " ").trim();
                        if (!next.isBlank()) {
                            String nextLow = next.toLowerCase(Locale.ROOT);
                            if (!nextLow.startsWith("by accepting")
                                    && !nextLow.startsWith("created:")
                                    && !nextLow.startsWith("page:")
                                    && next.length() <= 24
                                    && next.matches("[A-Za-z]+")) {
                                destinationStudio = next;
                                i = i + 1;
                            }
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
                    row.put("_src", "pdfText");
                    out.add(row);
                    extractedAny = true;
                }
            }
        } catch (Exception ex) {
            log.warn("[SALES-SAMPLE-ARTICLES][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static String extractPurchaseOrderTermsOfDeliveryPage1FromPdfBytes(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return "";

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            if (doc.getNumberOfPages() <= 0) return "";

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) return "";

            String[] lines = text.split("\\r?\\n");
            int headerIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                if (low.equals("terms of delivery") || low.startsWith("terms of delivery")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx < 0) return "";

            Pattern stopPat = Pattern.compile("(?i).*(time of delivery|quantity per artic|invoice average|sales sample|total quantity|purchase order detail).*");
            Pattern countryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*){2,}[A-Z]{2}$");

            String countryLine = "";
            StringBuilder body = new StringBuilder();
            for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 80); i++) {
                String raw = lines[i];
                if (raw == null) continue;
                String s = oneLine(raw).replaceAll("\\s+", " ").trim();
                if (s.isBlank()) continue;
                String sCompact = s.replaceAll("\\s+", "").trim();
                if (sCompact.equalsIgnoreCase("BY")) continue;
                if (stopPat.matcher(s).matches()) break;

                String upper = s.toUpperCase(Locale.ROOT);
                if (countryLine.isBlank() && countryLinePat.matcher(upper).matches()) {
                    countryLine = upper;
                    continue;
                }

                if (body.length() > 0) body.append("\n");
                body.append(s);
            }

            String cleanedBody = cleanPurchaseOrderTermsOfDeliveryText(body.toString());
            if (!countryLine.isBlank() && !cleanedBody.isBlank()) return countryLine + "\n" + cleanedBody;
            if (!countryLine.isBlank()) return countryLine;
            return cleanedBody;
        } catch (Exception ex) {
            log.warn("[PO-TERMS-DELIVERY][PDFBOX] failed: {}", ex.getMessage());
            return "";
        }
    }

    private static List<Map<String, String>> buildSalesSampleTermsOfDeliveryStaticAllPages(int pageCount, String termsOfDelivery) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pageCount <= 0) return out;
        String t = nvl(termsOfDelivery).trim();
        if (t.isBlank()) return out;
        for (int p = 1; p <= pageCount; p++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("page", String.valueOf(p));
            row.put("termsOfDelivery", t);
            row.put("_src", "staticCourier");
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderTermsOfDeliveryByPageFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            Pattern stopPat = Pattern.compile("(?i).*(time of delivery|quantity per artic|invoice average|sales sample|total quantity|purchase order detail).* ");
            Pattern countryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*){1,}[A-Z]{2}$");

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String low = s.toLowerCase(Locale.ROOT);
                    if (low.equals("terms of delivery") || low.startsWith("terms of delivery")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                String countryLine = "";
                StringBuilder body = new StringBuilder();
                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 80); i++) {
                    String raw = lines[i];
                    if (raw == null) continue;
                    String s = oneLine(raw).replaceAll("\\s+", " ").trim();
                    if (s.isBlank()) continue;
                    String sCompact = s.replaceAll("\\s+", "").trim();
                    if (sCompact.equalsIgnoreCase("BY")) continue;
                    if (stopPat.matcher(s + " ").matches()) break;

                    String upper = s.toUpperCase(Locale.ROOT);
                    if (countryLine.isBlank() && countryLinePat.matcher(upper).matches()) {
                        countryLine = upper;
                        continue;
                    }

                    if (body.length() > 0) body.append("\n");
                    body.append(s);
                }

                String cleanedBody = cleanPurchaseOrderTermsOfDeliveryText(body.toString());
                String combined;
                if (!countryLine.isBlank() && !cleanedBody.isBlank()) combined = countryLine + "\n" + cleanedBody;
                else if (!countryLine.isBlank()) combined = countryLine;
                else combined = cleanedBody;

                if (combined != null && !combined.isBlank()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("page", String.valueOf(page));
                    row.put("termsOfDelivery", combined);
                    row.put("_src", "pdfText");
                    out.add(row);
                }
            }
        } catch (Exception ex) {
            log.warn("[PO-TERMS-DELIVERY][PDFBOX] failed(byPage): {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractPurchaseOrderInvoiceAvgPriceFromPdfBytes(byte[] pdfBytes) {
        List<Map<String, String>> out = new ArrayList<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            Pattern headerPat = Pattern.compile("(?i).*invoice\\s+average.*");
            Pattern stopPat = Pattern.compile("(?i).*(terms of delivery|time of delivery|quantity per artic|sales sample|total quantity).*");
            Pattern rowSameLinePat = Pattern.compile("^\\s*([\\d\\.,]+)\\s+([A-Z]{3})\\s+(.*?)\\s*$");
            Pattern priceOnlyPat = Pattern.compile("^\\s*[\\d\\.,]+\\s+[A-Z]{3}\\s*$");
            Pattern hasCountryCodePat = Pattern.compile("\\b[A-Z]{2}\\b");

            int pageCount = doc.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text == null || text.isBlank()) continue;

                String[] lines = text.split("\\r?\\n");
                int headerIdx = -1;
                for (int i = 0; i < lines.length; i++) {
                    String s = lines[i] == null ? "" : oneLine(lines[i]).trim();
                    if (s.isBlank()) continue;
                    if (headerPat.matcher(s).matches() && s.toLowerCase(Locale.ROOT).contains("country")) {
                        headerIdx = i;
                        break;
                    }
                }
                if (headerIdx < 0) continue;

                String pendingPrice = "";
                for (int i = headerIdx + 1; i < Math.min(lines.length, headerIdx + 60); i++) {
                    String raw = lines[i];
                    if (raw == null) continue;
                    String line = oneLine(raw).replaceAll("\\s+", " ").trim();
                    if (line.isBlank()) continue;
                    if (stopPat.matcher(line).matches()) break;
                    String low = line.toLowerCase(Locale.ROOT);
                    if (low.contains("invoice average") && low.contains("country")) continue;

                    Matcher same = rowSameLinePat.matcher(line);
                    if (same.matches()) {
                        String price = (same.group(1).trim() + " " + same.group(2).trim()).trim();
                        String country = same.group(3) == null ? "" : same.group(3).trim();
                        if (!country.isBlank() && hasCountryCodePat.matcher(country.toUpperCase(Locale.ROOT)).find()) {
                            Map<String, String> row = new LinkedHashMap<>();
                            row.put("page", String.valueOf(page));
                            row.put("invoiceAveragePrice", price);
                            row.put("country", country);
                            row.put("_src", "pdfText");
                            out.add(row);
                            pendingPrice = "";
                            continue;
                        }
                    }

                    if (priceOnlyPat.matcher(line).matches()) {
                        pendingPrice = line.trim();
                        continue;
                    }

                    if (!pendingPrice.isBlank()) {
                        String country = line.trim();
                        if (hasCountryCodePat.matcher(country.toUpperCase(Locale.ROOT)).find()) {
                            Map<String, String> row = new LinkedHashMap<>();
                            row.put("page", String.valueOf(page));
                            row.put("invoiceAveragePrice", pendingPrice);
                            row.put("country", country);
                            row.put("_src", "pdfTextPaired");
                            out.add(row);
                        }
                        pendingPrice = "";
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[PO-INVOICE-PRICE][PDFBOX] failed: {}", ex.getMessage());
        }

        return out;
    }

    private static List<Map<String, String>> extractSalesSampleDestinationStudioAddressByPage(List<OcrNewLine> allLines) {
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
                if (low.equals("destination studio address") || low.startsWith("destination studio address")) {
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
                if (low.startsWith("time of delivery")) break;
                if (low.startsWith("article no")) break;
                if (low.startsWith("sales sample terms")) break;
                if (low.startsWith("by accepting")) break;
                if (low.startsWith("created:")) break;
                if (low.startsWith("page:")) break;

                // Exclude cancellation paragraph accidentally intersecting the address block
                if (low.startsWith("if the supplier fails")
                        || (low.contains("sales samples") && low.contains("time of delivery") && low.contains("right to cancel"))) {
                    continue;
                }

                s = s.replaceAll("(?i)\\bH&M\\b", "H & M");
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }

            String addr = sb.toString().trim();
            if (addr.isBlank()) continue;

            Map<String, String> row = new LinkedHashMap<>();
            row.put("page", String.valueOf(page));
            row.put("destinationStudioAddress", addr);
            out.add(row);
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

    private static void deduplicatePurchaseOrderInvoiceAvgPriceRows(List<Map<String, String>> rows) {
        if (rows == null || rows.size() < 2) return;

        Pattern singleCountryPat = Pattern.compile("^[A-Z]{2}$");
        Map<String, Map<String, String>> bestByPrice = new LinkedHashMap<>();
        Map<String, Integer> bestScoreByPrice = new LinkedHashMap<>();

        for (Map<String, String> r : rows) {
            if (r == null) continue;
            String price = nvl(r.get("invoiceAveragePrice")).trim();
            if (price.isBlank()) continue;

            String country = nvl(r.get("country")).trim();
            boolean isSingle = singleCountryPat.matcher(country).matches();
            boolean isBlank = country.isBlank();
            boolean isMulti = country.contains(",");

            String src = nvl(r.get("_src")).trim();
            int srcScore = 0;
            // Source quality: prefer direct table extraction over inferred fallbacks.
            if (src.equals("pdfText")) srcScore = 12000;
            else if (src.equals("pdfTextPaired")) srcScore = 11000;
            else if (src.equals("sameLine")) srcScore = 10000;
            else if (src.equals("paired")) srcScore = 8000;
            else if (src.equals("filled")) srcScore = 3000;
            else if (src.equals("blank")) srcScore = 500;
            else if (src.equals("terms")) srcScore = 100;
            else srcScore = 1000;

            // Higher is better
            int score = 0;
            score += srcScore;
            // If we have a multi-country list (comma-separated), prefer it over any single-country
            // candidates. This matches the PDF table format on page 1.
            if (isMulti) score += 2_000_000;
            // If the country is a single 2-letter code, strongly prefer keeping the earliest-page
            // instance so page-scoped UI filtering (page 1 view) does not hide a valid row.
            if (isSingle) score += 1_000_000;
            if (!isBlank) score += 100;
            if (!isMulti) score += 10;

            // Prefer earlier page if all else equal
            int page = 0;
            try {
                page = Integer.parseInt(nvl(r.get("page")).trim());
            } catch (Exception ignore) {
            }
            // Make page preference meaningful across pages (e.g. prefer page 1 filled row over
            // later-page duplicate even if later page is a stronger source).
            score -= Math.max(0, page) * 10_000;

            Integer bestScore = bestScoreByPrice.get(price);
            if (bestScore == null || score > bestScore) {
                bestScoreByPrice.put(price, score);
                bestByPrice.put(price, r);
            }
        }

        if (bestByPrice.isEmpty()) return;

        List<Map<String, String>> deduped = new ArrayList<>();
        Set<String> addedPrices = new HashSet<>();
        for (Map<String, String> r : rows) {
            if (r == null) continue;
            String price = nvl(r.get("invoiceAveragePrice")).trim();
            if (price.isBlank()) continue;
            Map<String, String> best = bestByPrice.get(price);
            if (best == null) continue;
            if (addedPrices.contains(price)) continue;

            // Keep the best row for this price.
            if (r == best) {
                deduped.add(r);
                addedPrices.add(price);
            } else {
                // Only add when we reach the best instance (preserve relative ordering).
                // So do nothing here.
            }
        }

        // If some best rows were never encountered (shouldn't happen), append them.
        for (Map.Entry<String, Map<String, String>> en : bestByPrice.entrySet()) {
            if (!addedPrices.contains(en.getKey())) {
                deduped.add(en.getValue());
                addedPrices.add(en.getKey());
            }
        }

        rows.clear();
        rows.addAll(deduped);
    }

    private static void normalizePurchaseOrderTermsOfDeliveryTextFromGlobal(
            List<Map<String, String>> poTermsOfDeliveryByPage,
            String globalTerms
    ) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;
        if (globalTerms == null || globalTerms.isBlank()) return;

        String globalBody = cleanPurchaseOrderTermsOfDeliveryText(stripLeadingCountryLine(globalTerms.trim()));
        Pattern countryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
        if (countryLinePat.matcher(globalBody).matches()) {
            globalBody = "";
        }
        if (globalBody.isBlank()) return;

        for (Map<String, String> row : poTermsOfDeliveryByPage) {
            if (row == null) continue;
            String page = row.get("page");

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

    private static void prefixPurchaseOrderTermsOfDeliveryCountriesFromInvoiceAvgPrice(
            List<Map<String, String>> poTermsOfDeliveryByPage,
            List<Map<String, String>> poInvoiceAvgPrice
    ) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;
        if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) return;

        java.util.function.Predicate<String> looksLikeCourierTerms = (s0) -> {
            String s = nvl(s0).trim().toLowerCase(Locale.ROOT);
            if (s.isBlank()) return false;
            boolean hasCourier = s.contains("courier") || s.contains("dhl") || s.contains("destination studio")
                    || s.contains("account number") || s.contains("account no");
            boolean hasIncoterms = s.contains("incoterms") || s.contains("transport by") || s.contains("packing mode")
                    || s.contains("free carrier") || s.contains("fca") || s.contains("fob") || s.contains("ship by");
            return hasCourier && !hasIncoterms;
        };

        Map<String, LinkedHashSet<String>> countriesByPage = new LinkedHashMap<>();
        Pattern countryListPat = Pattern.compile("\\b[A-Z]{2}\\b");
        for (Map<String, String> row : poInvoiceAvgPrice) {
            if (row == null) continue;
            String page = row.get("page");
            if (page == null || page.isBlank()) page = "1";
            String country = row.get("country");
            if (country == null || country.isBlank()) continue;

            LinkedHashSet<String> countries = countriesByPage.computeIfAbsent(page.trim(), k -> new LinkedHashSet<>());
            Matcher m = countryListPat.matcher(country.toUpperCase(Locale.ROOT));
            while (m.find()) {
                countries.add(m.group());
            }
        }

        Pattern leadingCountryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");

        // Ensure each page that has invoice-derived countries also has a Terms-of-Delivery row.
        // Some PDFs lose the Terms-of-Delivery body on certain pages (e.g. page 4), so we need
        // a placeholder row (country line) for later normalization with the global body.
        Set<String> existingPages = new HashSet<>();
        for (Map<String, String> r : poTermsOfDeliveryByPage) {
            if (r == null) continue;
            String p = r.get("page");
            if (p == null || p.isBlank()) continue;
            existingPages.add(p.trim());
        }
        for (Map.Entry<String, LinkedHashSet<String>> en : countriesByPage.entrySet()) {
            String page = en.getKey();
            if (page == null || page.isBlank()) continue;
            if (page.trim().equals("1")) continue;
            LinkedHashSet<String> countries = en.getValue();
            if (countries == null || countries.isEmpty()) continue;
            if (existingPages.contains(page.trim())) continue;

            // Do not inject placeholder Terms-of-Delivery rows for courier-only pages.
            // These pages often contain shipping/account information without any country line.
            // Keeping them empty avoids showing misleading country codes in the UI.
            // (If such a page actually needs ToD, it should be extracted from the PDF text itself.)
            // NOTE: This block only controls placeholder creation; real extracted rows remain untouched.
            //
            // Since we don't have the page's ToD content here, we skip placeholder creation entirely.
            // (Placeholders are best-effort only and can confuse the last page.)
            continue;
        }

        for (Map<String, String> row : poTermsOfDeliveryByPage) {
            if (row == null) continue;
            String page = row.get("page");
            if (page == null || page.isBlank()) page = "1";
            if (page.trim().equals("1")) continue;
            LinkedHashSet<String> countries = countriesByPage.get(page.trim());
            if (countries == null || countries.isEmpty()) continue;

            String existing = row.get("termsOfDelivery");
            if (looksLikeCourierTerms.test(existing)) continue;
            String countryLine = String.join(", ", countries);
            if (existing == null || existing.isBlank()) {
                row.put("termsOfDelivery", countryLine);
                continue;
            }

            String trimmed = existing.trim();
            String[] parts = trimmed.split("\\R", 2);
            String firstLine = parts.length > 0 ? parts[0].trim() : "";
            if (leadingCountryLinePat.matcher(firstLine).matches()) continue;

            row.put("termsOfDelivery", countryLine + "\n" + trimmed);
        }
    }

    private static String extractCourierTermsBlockFromPurchaseOrderTermsByPage(List<Map<String, String>> poTermsOfDeliveryByPage) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return "";

        String best = "";
        int bestPage = -1;
        for (Map<String, String> r : poTermsOfDeliveryByPage) {
            if (r == null) continue;
            String terms = nvl(r.get("termsOfDelivery")).trim();
            if (terms.isBlank()) continue;

            String low = terms.toLowerCase(Locale.ROOT);
            boolean hasCourier = low.contains("courier") || low.contains("dhl") || low.contains("destination studio")
                    || low.contains("account number") || low.contains("account no");
            boolean hasStrongIncoterms = low.contains("incoterms") || low.contains("packing mode")
                    || low.contains("free carrier") || low.contains("fca") || low.contains("fob")
                    || (low.contains("ship by") && !low.contains("courier"));
            boolean isTransportByCourier = low.contains("transport by") && low.contains("courier");
            if (!(hasCourier && (!hasStrongIncoterms || isTransportByCourier))) continue;

            int page = 0;
            try {
                page = Integer.parseInt(nvl(r.get("page")).trim());
            } catch (Exception ignore) {
            }
            if (page <= 0) page = 1;

            if (page > bestPage) {
                bestPage = page;
                best = terms;
            }
        }
        return nvl(best).trim();
    }

    private static String inferSalesSampleCourierTermsBlockFromAllLines(List<OcrNewLine> allLines) {
        if (allLines == null || allLines.isEmpty()) return "";
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (OcrNewLine l : allLines) {
            if (l == null) continue;
            String s = nvl(l.getText()).trim();
            if (s.isBlank()) continue;
            String low = oneLine(s).toLowerCase(Locale.ROOT);
            if (low.contains("transport by courier") || low.contains("account number to be used")) {
                keep.add(oneLine(s).replaceAll("\\s+", " ").trim());
            }
        }
        if (keep.isEmpty()) return "";
        return String.join("\n", keep).trim();
    }

    private static void removeCourierTermsFromPurchaseOrderTermsByPage(List<Map<String, String>> poTermsOfDeliveryByPage) {
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;

        List<Map<String, String>> kept = new ArrayList<>();
        for (Map<String, String> r : poTermsOfDeliveryByPage) {
            if (r == null) continue;
            String terms = nvl(r.get("termsOfDelivery")).trim();
            if (terms.isBlank()) {
                kept.add(r);
                continue;
            }

            String low = terms.toLowerCase(Locale.ROOT);
            boolean hasCourier = low.contains("courier") || low.contains("dhl") || low.contains("destination studio")
                    || low.contains("account number") || low.contains("account no");
            boolean hasStrongIncoterms = low.contains("incoterms") || low.contains("packing mode")
                    || low.contains("free carrier") || low.contains("fca") || low.contains("fob")
                    || (low.contains("ship by") && !low.contains("courier"));
            boolean isTransportByCourier = low.contains("transport by") && low.contains("courier");
            boolean isCourierTerms = hasCourier && (!hasStrongIncoterms || isTransportByCourier);
            if (isCourierTerms) {
                continue;
            }
            kept.add(r);
        }
        poTermsOfDeliveryByPage.clear();
        poTermsOfDeliveryByPage.addAll(kept);
    }

    private static void upsertSalesSampleTermsAllPages(List<Map<String, String>> salesSampleTermsByPage, int pageCount, String staticText) {
        if (salesSampleTermsByPage == null) return;
        if (pageCount <= 0) return;
        String t = nvl(staticText).trim();
        if (t.isBlank()) return;

        Map<String, Map<String, String>> byPage = new LinkedHashMap<>();
        for (Map<String, String> r : salesSampleTermsByPage) {
            if (r == null) continue;
            String p = nvl(r.get("page")).trim();
            if (p.isBlank()) p = "1";
            byPage.put(p, r);
        }

        for (int p = 1; p <= pageCount; p++) {
            String ps = String.valueOf(p);
            Map<String, String> cur = byPage.get(ps);
            if (cur == null) {
                Map<String, String> nr = new LinkedHashMap<>();
                nr.put("page", ps);
                nr.put("salesSampleTerms", t);
                nr.put("_src", "staticCourier");
                salesSampleTermsByPage.add(nr);
                byPage.put(ps, nr);
            } else {
                String existing = nvl(cur.get("salesSampleTerms")).trim();
                if (existing.isBlank()) {
                    cur.put("salesSampleTerms", t);
                    cur.put("_src", "staticCourier");
                } else if (!existing.contains(t)) {
                    cur.put("salesSampleTerms", (existing + "\n" + t).trim());
                }
            }
        }
    }

    private static String chooseStaticSalesSampleTermsText(List<Map<String, String>> salesSampleTermsByPage) {
        if (salesSampleTermsByPage == null || salesSampleTermsByPage.isEmpty()) return "";

        String best = "";
        int bestLen = -1;
        for (Map<String, String> r : salesSampleTermsByPage) {
            if (r == null) continue;
            String v = nvl(r.get("salesSampleTerms")).trim();
            if (v.isBlank()) continue;
            if (v.length() > bestLen) {
                bestLen = v.length();
                best = v;
            }
        }
        return nvl(best).trim();
    }

    private static void upsertSalesSampleTermsStaticAllPages(List<Map<String, String>> salesSampleTermsByPage, int pageCount, String staticTerms) {
        if (salesSampleTermsByPage == null) return;
        if (pageCount <= 0) return;
        String t = nvl(staticTerms).trim();
        if (t.isBlank()) return;

        Map<String, Map<String, String>> byPage = new LinkedHashMap<>();
        for (Map<String, String> r : salesSampleTermsByPage) {
            if (r == null) continue;
            String p = nvl(r.get("page")).trim();
            if (p.isBlank()) p = "1";
            byPage.put(p, r);
        }

        for (int p = 1; p <= pageCount; p++) {
            String ps = String.valueOf(p);
            Map<String, String> cur = byPage.get(ps);
            if (cur == null) {
                Map<String, String> nr = new LinkedHashMap<>();
                nr.put("page", ps);
                nr.put("salesSampleTerms", t);
                nr.put("_src", "static");
                salesSampleTermsByPage.add(nr);
                byPage.put(ps, nr);
            } else {
                cur.put("salesSampleTerms", t);
                cur.put("_src", "static");
            }
        }
    }

    private static String chooseStaticSalesSampleTimeOfDeliveryText(List<Map<String, String>> salesSampleTimeOfDeliveryByPage) {
        if (salesSampleTimeOfDeliveryByPage == null || salesSampleTimeOfDeliveryByPage.isEmpty()) return "";

        String best = "";
        int bestLen = -1;
        for (Map<String, String> r : salesSampleTimeOfDeliveryByPage) {
            if (r == null) continue;
            String v = nvl(r.get("timeOfDelivery")).trim();
            if (v.isBlank()) continue;
            if (v.length() > bestLen) {
                bestLen = v.length();
                best = v;
            }
        }
        return nvl(best).trim();
    }

    private static void upsertSalesSampleTimeOfDeliveryStaticAllPages(List<Map<String, String>> salesSampleTimeOfDeliveryByPage, int pageCount, String staticTod) {
        if (salesSampleTimeOfDeliveryByPage == null) return;
        if (pageCount <= 0) return;
        String t = nvl(staticTod).trim();
        if (t.isBlank()) return;

        Map<String, Map<String, String>> byPage = new LinkedHashMap<>();
        for (Map<String, String> r : salesSampleTimeOfDeliveryByPage) {
            if (r == null) continue;
            String p = nvl(r.get("page")).trim();
            if (p.isBlank()) p = "1";
            byPage.put(p, r);
        }

        for (int p = 1; p <= pageCount; p++) {
            String ps = String.valueOf(p);
            Map<String, String> cur = byPage.get(ps);
            if (cur == null) {
                Map<String, String> nr = new LinkedHashMap<>();
                nr.put("page", ps);
                nr.put("timeOfDelivery", t);
                nr.put("_src", "static");
                salesSampleTimeOfDeliveryByPage.add(nr);
                byPage.put(ps, nr);
            } else {
                cur.put("timeOfDelivery", t);
                cur.put("_src", "static");
            }
        }
    }

    private static String chooseStaticSalesSampleTermsOfDeliveryText(List<Map<String, String>> salesSampleTermsOfDeliveryByPage) {
        if (salesSampleTermsOfDeliveryByPage == null || salesSampleTermsOfDeliveryByPage.isEmpty()) return "";

        String best = "";
        int bestPage = -1;
        int bestLen = -1;
        for (Map<String, String> r : salesSampleTermsOfDeliveryByPage) {
            if (r == null) continue;
            String v = nvl(r.get("termsOfDelivery")).trim();
            if (v.isBlank()) continue;

            int page = 0;
            try {
                page = Integer.parseInt(nvl(r.get("page")).trim());
            } catch (Exception ignore) {
            }
            if (page <= 0) page = 1;

            if (page > bestPage || (page == bestPage && v.length() > bestLen)) {
                bestPage = page;
                bestLen = v.length();
                best = v;
            }
        }
        return nvl(best).trim();
    }

    private static void upsertSalesSampleTermsOfDeliveryStaticAllPages(List<Map<String, String>> salesSampleTermsOfDeliveryByPage, int pageCount, String staticTermsOfDelivery) {
        if (salesSampleTermsOfDeliveryByPage == null) return;
        if (pageCount <= 0) return;
        String t = nvl(staticTermsOfDelivery).trim();
        if (t.isBlank()) return;

        Map<String, Map<String, String>> byPage = new LinkedHashMap<>();
        for (Map<String, String> r : salesSampleTermsOfDeliveryByPage) {
            if (r == null) continue;
            String p = nvl(r.get("page")).trim();
            if (p.isBlank()) p = "1";
            byPage.put(p, r);
        }

        for (int p = 1; p <= pageCount; p++) {
            String ps = String.valueOf(p);
            Map<String, String> cur = byPage.get(ps);
            if (cur == null) {
                Map<String, String> nr = new LinkedHashMap<>();
                nr.put("page", ps);
                nr.put("termsOfDelivery", t);
                nr.put("_src", "static");
                salesSampleTermsOfDeliveryByPage.add(nr);
                byPage.put(ps, nr);
            } else {
                cur.put("termsOfDelivery", t);
                cur.put("_src", "static");
            }
        }
    }

    private static void fillPurchaseOrderInvoiceAvgPriceCountriesFromTermsByPage(
            List<Map<String, String>> poInvoiceAvgPrice,
            List<Map<String, String>> poTermsOfDeliveryByPage
    ) {
        if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) return;
        if (poTermsOfDeliveryByPage == null || poTermsOfDeliveryByPage.isEmpty()) return;

        Map<String, Set<String>> termsCountriesByPage = new LinkedHashMap<>();
        for (Map<String, String> r : poTermsOfDeliveryByPage) {
            if (r == null) continue;
            String p = nvl(r.get("page")).trim();
            if (p.isBlank() || p.equals("1")) continue;
            String terms = nvl(r.get("termsOfDelivery")).trim();
            if (terms.isBlank()) continue;
            Set<String> codes = extractAnyTwoLetterCodesFromTermsFirstLine(terms);
            if (codes == null || codes.isEmpty()) continue;
            termsCountriesByPage.put(p, new LinkedHashSet<>(codes));
        }
        if (termsCountriesByPage.isEmpty()) return;

        Pattern codePat = Pattern.compile("\\b([A-Z]{2})\\b");
        Map<String, List<Map<String, String>>> invoiceRowsByCountry = new LinkedHashMap<>();
        List<Map<String, String>> invoiceRowsWithPrice = new ArrayList<>();
        for (Map<String, String> r : poInvoiceAvgPrice) {
            if (r == null) continue;
            String price = nvl(r.get("invoiceAveragePrice")).trim();
            if (price.isBlank()) continue;
            invoiceRowsWithPrice.add(r);

            String countryField = nvl(r.get("country")).toUpperCase(Locale.ROOT);
            Matcher m = codePat.matcher(countryField);
            while (m.find()) {
                String c = m.group(1);
                if (c == null || c.isBlank()) continue;
                invoiceRowsByCountry.computeIfAbsent(c, k -> new ArrayList<>()).add(r);
            }
        }
        if (invoiceRowsWithPrice.isEmpty()) return;

        Set<String> existingKeys = new HashSet<>();
        for (Map<String, String> r : poInvoiceAvgPrice) {
            if (r == null) continue;
            String key = nvl(r.get("page")).trim() + "|" + nvl(r.get("invoiceAveragePrice")).trim() + "|" + nvl(r.get("country")).trim();
            existingKeys.add(key);
        }

        for (Map.Entry<String, Set<String>> en : termsCountriesByPage.entrySet()) {
            String page = en.getKey();
            if (page == null || page.isBlank()) continue;

            Set<String> wantedCodes = en.getValue();
            if (wantedCodes == null || wantedCodes.isEmpty()) continue;

            for (String code : wantedCodes) {
                if (code == null || code.isBlank()) continue;
                String c = code.trim().toUpperCase(Locale.ROOT);

                List<Map<String, String>> candidates = invoiceRowsByCountry.get(c);
                Map<String, String> chosen = (candidates != null && !candidates.isEmpty()) ? candidates.get(0) : null;
                if (chosen == null) {
                    // Fallback: if no explicit match exists, still provide a row using the first known price.
                    chosen = invoiceRowsWithPrice.get(0);
                }

                String price = nvl(chosen.get("invoiceAveragePrice")).trim();
                if (price.isBlank()) continue;

                String key = page.trim() + "|" + price + "|" + c;
                if (existingKeys.contains(key)) continue;

                Map<String, String> row = new LinkedHashMap<>();
                row.put("page", page.trim());
                row.put("invoiceAveragePrice", price);
                row.put("country", c);
                row.put("_src", (candidates != null && !candidates.isEmpty()) ? "termsMatch" : "termsOnly");
                poInvoiceAvgPrice.add(row);
                existingKeys.add(key);
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
            int cut = lastRelevantIdx;
            while (cut + 1 < out.size()) {
                String prev = nvl(out.get(cut)).trim();
                String next = nvl(out.get(cut + 1)).trim();
                if (next.isBlank()) break;

                String nextLow = next.toLowerCase(Locale.ROOT);
                boolean continuation = nextLow.startsWith("charges")
                        || nextLow.startsWith("customs")
                        || nextLow.startsWith("clearance")
                        || nextLow.startsWith("duty")
                        || nextLow.startsWith("freight")
                        || nextLow.startsWith("are ")
                        || nextLow.startsWith("intercom")
                        || (!prev.endsWith("."))
                        || prev.endsWith(",");
                if (!continuation) break;
                cut++;
            }
            out = new ArrayList<>(out.subList(0, cut + 1));
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

        String termsFirstLine = null;
        List<String> baseOrderedFromTerms = new ArrayList<>();
        String baseFallbackCountryList = "";
        if (termsWithCountries != null && !termsWithCountries.isBlank()) {
            termsFirstLine = termsWithCountries.split("\\R", 2)[0].trim();

            // Preserve PDF/Terms ordering by splitting on commas.
            String[] parts = termsFirstLine.split("\\s*,\\s*");
            for (String p : parts) {
                if (p == null) continue;
                String code = p.trim().toUpperCase(Locale.ROOT);
                if (!code.matches("^[A-Z]{2}$")) continue;
                if (!baseOrderedFromTerms.contains(code)) baseOrderedFromTerms.add(code);
            }
        }

        if (!baseOrderedFromTerms.isEmpty()) {
            baseFallbackCountryList = String.join(", ", baseOrderedFromTerms);
        }

        // Only overwrite weak sources to avoid clobbering strong OCR-extracted countries.
        Pattern singleCountryPat = Pattern.compile("^[A-Z]{2}$");
        boolean anyOverwritten = false;
        for (Map<String, String> r : poInvoiceAvgPrice) {
            if (r == null) continue;
            String price = nvl(r.get("invoiceAveragePrice")).trim();
            if (price.isBlank()) continue;

            String src = nvl(r.get("_src")).trim();
            String cur = nvl(r.get("country")).trim();

            boolean weakSrc = src.isBlank() || src.equals("blank") || src.equals("filled") || src.equals("terms");
            boolean curBlankOrSingle = cur.isBlank() || singleCountryPat.matcher(cur).matches();
            if (weakSrc && curBlankOrSingle) {
                // Build a price-specific fallback list:
                // - base list from Terms (ordered)
                // - plus strong same-price codes (sameLine/paired) from other pages
                // - minus codes that already have their own single-country row for OTHER prices
                LinkedHashSet<String> set = new LinkedHashSet<>();
                for (String c0 : baseOrderedFromTerms) set.add(c0);

                Pattern codePat = Pattern.compile("\\b([A-Z]{2})\\b");
                for (Map<String, String> rr : poInvoiceAvgPrice) {
                    if (rr == null) continue;
                    String p = nvl(rr.get("invoiceAveragePrice")).trim();
                    if (!p.equals(price)) continue;
                    String ssrc = nvl(rr.get("_src")).trim();
                    boolean strong = ssrc.equals("sameLine") || ssrc.equals("paired");
                    if (!strong) continue;
                    String cc = nvl(rr.get("country")).trim().toUpperCase(Locale.ROOT);
                    Matcher mm = codePat.matcher(cc);
                    while (mm.find()) {
                        String code = mm.group(1);
                        if (code != null && !code.isBlank()) set.add(code.trim());
                    }
                }

                Set<String> excluded = new HashSet<>();
                for (Map<String, String> rr : poInvoiceAvgPrice) {
                    if (rr == null) continue;
                    String p = nvl(rr.get("invoiceAveragePrice")).trim();
                    if (p.isBlank() || p.equals(price)) continue;
                    String cc = nvl(rr.get("country")).trim().toUpperCase(Locale.ROOT);
                    if (singleCountryPat.matcher(cc).matches()) excluded.add(cc);
                }
                set.removeAll(excluded);

                String priceFallbackCountryList = String.join(", ", set);
                if (priceFallbackCountryList.isBlank()) continue;

                r.put("country", priceFallbackCountryList);
                r.put("_src", "terms");
                anyOverwritten = true;
            }
        }
        if (anyOverwritten) return;

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            boolean looksLikeHeader = (low.contains("invoice average") || low.contains("invoice avg"))
                    && low.contains("country");
            if (looksLikeHeader) {
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

        Set<String> existingPrices = new LinkedHashSet<>();
        Set<String> existingPricesNorm = new LinkedHashSet<>();
        for (Map<String, String> r : poInvoiceAvgPrice) {
            String p = r == null ? null : r.get("invoiceAveragePrice");
            if (p == null) continue;
            String pp = p.trim();
            if (!pp.isBlank()) {
                existingPrices.add(pp);
                existingPricesNorm.add(pp.replaceAll("\\s+", ""));
            }
        }

        Map<String, List<String>> existingCountriesByPrice = new LinkedHashMap<>();
        for (Map<String, String> r : poInvoiceAvgPrice) {
            String p = r == null ? null : r.get("invoiceAveragePrice");
            if (p == null || p.isBlank()) continue;
            String c = r.get("country");
            existingCountriesByPrice.computeIfAbsent(p.trim(), k -> new ArrayList<>());
            if (c != null && !c.isBlank()) {
                existingCountriesByPrice.get(p.trim()).add(c.trim());
            }
        }

        for (String price : detectedPrices) {
            String priceNorm = price == null ? "" : price.replaceAll("\\s+", "");
            if (existingPrices.contains(price) || (!priceNorm.isBlank() && existingPricesNorm.contains(priceNorm))) {
                // A row for this price already exists (even if the country is blank).
                // Do not inject a multi-country fallback row from Terms of Delivery.
                continue;
            }
            List<String> existingCountries = existingCountriesByPrice.get(price);
            if (existingCountries != null && !existingCountries.isEmpty()) {
                // We already have at least one country mapped for this price.
                // Do not inject an extra multi-country fallback row because it pollutes
                // the Invoice Avg Price table in the UI.
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("invoiceAveragePrice", price);
            row.put("country", baseFallbackCountryList);
            row.put("_src", "terms");
            int insertAt = 1;
            if (poInvoiceAvgPrice == null) return;
            if (insertAt > poInvoiceAvgPrice.size()) insertAt = poInvoiceAvgPrice.size();
            poInvoiceAvgPrice.add(insertAt, row);
            log.info("[PO-INVOICE-PRICE] Filled missing row from Terms of Delivery: {} | {}", price, baseFallbackCountryList);
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

        int colourLeftMin = 900;
        int colourLeftMax = Integer.MAX_VALUE;
        for (int i = headerIdx; i < Math.min(allLines.size(), headerIdx + 15); i++) {
            OcrNewLine l = allLines.get(i);
            if (l == null || l.getText() == null) continue;
            if (l.getPage() != headerPage) break;
            String low = oneLine(l.getText()).toLowerCase(Locale.ROOT);
            if (low.equals("colour") || low.startsWith("colour ") || low.contains(" colour ")) {
                colourLeftMin = Math.max(0, l.getLeft() - 60);
            }
            if (low.contains("graphicalappearance") || low.contains("graphical appearance")) {
                colourLeftMax = Math.max(colourLeftMin + 100, l.getLeft() - 30);
            }
        }

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
            if (low.contains("graphicalappearance") || low.contains("graphical appearance")) continue;
            if (low.contains("total quantity") || low.contains("invoice average") || low.contains("time of delivery")) break;
            if (costQtyPat.matcher(s).find()) continue;

            // Only capture text from the Colour column region (avoids accidentally concatenating other columns).
            if (l.getLeft() < colourLeftMin) continue;
            if (l.getLeft() >= colourLeftMax) continue;

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

    private static void normalizePurchaseOrderQuantityPerArticleGraphicalAppearance(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) return;

        // Common Graphical Appearance values observed in H&M Purchase Order PDFs.
        Pattern trailingGaPat = Pattern.compile("^(.*?)(?:\\s+)(All\\s+over\\s+pattern|Solid|Placement\\s+print|Stripe|Check)\\s*$", Pattern.CASE_INSENSITIVE);

        for (Map<String, String> r : rows) {
            if (r == null) continue;

            String colour = nvl(r.get("colour")).replaceAll("\\s+", " ").trim();
            String ga = nvl(r.get("graphicalAppearance")).replaceAll("\\s+", " ").trim();
            String optionNo = nvl(r.get("optionNo")).replaceAll("\\s+", " ").trim();

            // If this row already has Option No (some PO templates), do not try to derive GraphicalAppearance
            // from the colour tail.
            if (!optionNo.isBlank()) continue;

            // If Colour already contains the GraphicalAppearance tail, split it out.
            if (!colour.isBlank() && ga.isBlank()) {
                Matcher m = trailingGaPat.matcher(colour);
                if (m.find()) {
                    String c = nvl(m.group(1)).replaceAll("\\s+", " ").trim();
                    String g = nvl(m.group(2)).replaceAll("\\s+", " ").trim();
                    if (!c.isBlank() && !g.isBlank()) {
                        r.put("colour", c);
                        r.put("graphicalAppearance", g);
                        continue;
                    }
                }
            }

            // If both are present but colour ends with ga, de-duplicate.
            if (!colour.isBlank() && !ga.isBlank()) {
                String lowColour = colour.toLowerCase(Locale.ROOT);
                String lowGa = ga.toLowerCase(Locale.ROOT);
                if (lowColour.endsWith(" " + lowGa)) {
                    String c = colour.substring(0, colour.length() - (ga.length() + 1)).trim();
                    if (!c.isBlank()) r.put("colour", c);
                }
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

    private static Set<String> extractAnyTwoLetterCodesFromTermsFirstLine(String terms) {
        if (terms == null || terms.isBlank()) return Set.of();
        String t = terms.trim();
        String firstLine = t.split("\\R", 2)[0].trim();
        if (firstLine.isBlank()) return Set.of();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b([A-Z]{2})\\b").matcher(firstLine.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String code = m.group(1);
            if (code != null && !code.isBlank()) {
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
     *   <ul>
     *     <li>Adult letter sizes: XS, S, M, L, XL, XXS, XXL — with optional
     *         trailing punctuation that OCR may produce in place of '*'
     *         (e.g. '*', '+', '.', "'", '"') and an optional "/P" or "IP"
     *         suffix (Tesseract often misreads '/' as 'I').</li>
     *     <li>H&amp;M baby/kids age-range labels: forms like
     *         {@code 0-1M}, {@code 1-2M}, {@code 2-4M}, {@code 9-12M},
     *         {@code 12-18M}, {@code 1½-2Y}, {@code 2-3Y}, {@code 10-12Y},
     *         with an optional parenthesized cm size such as
     *         {@code (50)}, {@code (56)}, … , {@code (152)} and an optional
     *         trailing '*'. Covers both the merged form {@code "0-1M(50)*"}
     *         and the bare range form {@code "0-1M"}.</li>
     *   </ul>
     * Examples that match:
     *   {@code "XS"}, {@code "S*"}, {@code "M/P"}, {@code "XL/P*"},
     *   {@code "XS+"}, {@code "XLIP"}, {@code "M."}, {@code "S/P."},
     *   {@code "0-1M(50)*"}, {@code "1-2M(56)*"}, {@code "9-12M(80)*"},
     *   {@code "1½-2Y(92)*"}, {@code "2-3Y(98)*"}, {@code "0-1M"}, {@code "1½-2Y"}.
     */
    private static final Pattern SIZE_LABEL_PAT = Pattern.compile(
            "(?i)^(?:" +
                    // Adult letter sizes
                    "(?:XX?S|S|M|L|XX?L)(?:[*+.,'\"\u2022])?(?:[/I]P(?:[*+.,'\"\u2022])?)?" +
                    "|" +
                    // H&M baby/kids age-range labels: 0-1M, 1½-2Y, optional (cm)*
                    "\\d{1,2}(?:\u00BD|1/2)?-\\d{1,2}[MY](?:\\(\\d{2,3}\\)[*+.,'\"\u2022]?)?" +
                    ")$");

    /**
     * Match JUST the bare age-range piece of a kids size label, e.g. "0-1M",
     * "1½-2Y". Used to merge the range token with an adjacent "(cm)*" token
     * when PDFBox/OCR split them on whitespace.
     */
    private static final Pattern KIDS_AGE_RANGE_PAT = Pattern.compile(
            "(?i)^\\d{1,2}(?:\u00BD|1/2)?-\\d{1,2}[MY]$");

    /** Match the cm-suffix piece of a kids size label, e.g. "(50)", "(92)*". */
    private static final Pattern KIDS_CM_SUFFIX_PAT = Pattern.compile(
            "^\\(\\d{2,3}\\)[*+.,'\"\u2022]?$");

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
                    // H&M kids age-range + "(cm)*" → merged single size label.
                    // PDFBox frequently emits them as two adjacent tokens.
                    if (KIDS_AGE_RANGE_PAT.matcher(tok).matches()
                            && KIDS_CM_SUFFIX_PAT.matcher(next).matches()) {
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
            String tok = tokens[t];
            if (tok == null || tok.isEmpty()) continue;
            // Ignore non-qty tokens like H&M colour code "12-201" or alphanumeric noise.
            if (tok.indexOf('-') >= 0) continue;
            boolean hasLetter = false;
            for (int i = 0; i < tok.length(); i++) {
                if (Character.isLetter(tok.charAt(i))) {
                    hasLetter = true;
                    break;
                }
            }
            if (hasLetter) continue;

            // Allow OCR thousand separators like apostrophes: 1'010 -> 1010
            String norm = normalizeNumberToken(tok);
            if (!norm.isBlank()) {
                intIndices.add(t);
                intValues.add(norm);
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
        m.put("article", normalizeColourSizeBreakdownArticle(article));
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

    private static String normalizeColourSizeBreakdownArticle(String article0) {
        String article = nvl(article0).trim();
        if (article.isBlank()) return article;

        // Preserve the PDF-visible label. Some rows embed extra tokens, but the
        // stable identifier shown on the PDF is typically:
        //   Article No (3 digits) + H&M Colour Code (NN-NNN)
        // Example: "001 22-216".
        // If both parts are present, return that combined label.
        Matcher m = Pattern.compile("\\b(\\d{3})\\b.*?\\b(\\d{2}-\\d{3})\\b").matcher(article);
        if (m.find()) return (m.group(1) + " " + m.group(2)).trim();

        return article;
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

        List<Map<String, String>> rows = extractColourSizeBreakdownRowsFromText(pageText);
        if (rows == null || rows.isEmpty()) return null;
        return rows.get(0);
    }

    private static List<Map<String, String>> extractColourSizeBreakdownRowsFromText(String pageText) {
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

        List<Map<String, String>> out = new ArrayList<>();
        Pattern artStart = Pattern.compile("^\\d{3}$");
        Pattern artCode = Pattern.compile("^\\d{2}-\\d{3}$");
        while (t < tokens.size()) {
            String tok = tokens.get(t);
            String lowTok = tok.toLowerCase(Locale.ROOT);
            if (lowTok.equals("article")) break;
            if (lowTok.startsWith("total")) break;
            if (lowTok.equals("breakdown")) break;

            String a0 = tokens.get(t);
            String a1 = (t + 1) < tokens.size() ? tokens.get(t + 1) : "";
            String article;
            if (artStart.matcher(a0).matches() && artCode.matcher(a1).matches()) {
                article = a0 + " " + a1;
                t += 2;
            } else {
                List<String> atoks = new ArrayList<>();
                while (t < tokens.size()) {
                    String ttok = tokens.get(t);
                    String l2 = ttok.toLowerCase(Locale.ROOT);
                    if (l2.equals("article") || l2.startsWith("total")) break;
                    if (ttok.matches("\\d+")) break;
                    if (SIZE_LABEL_PAT.matcher(ttok).matches()) {
                        t++;
                        continue;
                    }
                    atoks.add(ttok);
                    t++;
                }
                article = String.join(" ", atoks).trim();
            }

            if (article.isBlank()) {
                if (t < tokens.size() && tokens.get(t).matches("\\d+")) {
                    t++;
                    continue;
                }
                break;
            }

            List<String> values = new ArrayList<>();
            while (t < tokens.size() && values.size() < n) {
                String vtok = tokens.get(t);
                String l3 = vtok.toLowerCase(Locale.ROOT);
                if (l3.equals("article") || l3.startsWith("total")) break;
                if (vtok.indexOf('-') >= 0) {
                    t++;
                    continue;
                }
                boolean hasLetter = false;
                for (int i = 0; i < vtok.length(); i++) {
                    if (Character.isLetter(vtok.charAt(i))) {
                        hasLetter = true;
                        break;
                    }
                }
                if (hasLetter) {
                    t++;
                    continue;
                }
                String vv = normalizeNumberToken(vtok);
                if (!vv.isBlank()) values.add(vv);
                t++;
            }
            if (values.size() < n) break;

            Map<String, String> m = new LinkedHashMap<>();
            m.put("article", normalizeColourSizeBreakdownArticle(article));
            long sum = 0;
            for (int s = 0; s < n; s++) {
                String key = sizeKeys.get(s);
                String val = values.get(s);
                m.put(key, val);
                try { sum += Long.parseLong(val); } catch (NumberFormatException ignore) { /* ignore */ }
            }
            m.put("total", String.valueOf(sum));
            out.add(m);
        }
        return out;
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

                    List<Map<String, String>> rows = extractColourSizeBreakdownRowsFromText(pageText);
                    if (rows != null && !rows.isEmpty()) {
                        log.info("[CSB][PDFBOX] sortByPos={} page={} parsed rows={} firstRow={} ",
                                sortByPos, p, rows.size(), rows.get(0));
                        return rows;
                    }
                }

                // Full-doc pass within this sort mode.
                stripper.setStartPage(1);
                stripper.setEndPage(pageCount);
                String allText = stripper.getText(doc);
                List<Map<String, String>> rows = extractColourSizeBreakdownRowsFromText(allText);
                if (rows != null && !rows.isEmpty()) {
                    log.info("[CSB][PDFBOX] sortByPos={} full-doc parsed rows={} firstRow={}", sortByPos, rows.size(), rows.get(0));
                    return rows;
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
        if (digits.length() >= 6 && (digits.length() % 2 == 0)) {
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

            // Some PDFs contain multiple articles in the same Section 2 table, with qty columns per-article.
            // Detect article numbers so we can split size rows into one output row per article.
            List<String> articleNos = new ArrayList<>();
            {
                Pattern articleHdr = Pattern.compile("(?i)^\\s*article\\s*no\\s*[:#]?\\s*(.+?)\\s*$");
                for (int i = Math.max(0, idxHeader - 40); i < Math.min(texts.size(), idxHeader + 5); i++) {
                    String t = oneLine(texts.get(i));
                    if (t.isBlank()) continue;
                    Matcher ah = articleHdr.matcher(t);
                    if (!ah.matches()) continue;
                    String tail = nvl(ah.group(1));
                    Matcher am = Pattern.compile("\\b\\d{3}\\b").matcher(tail);
                    while (am.find()) {
                        String a = am.group().trim();
                        if (!a.isBlank() && !articleNos.contains(a)) articleNos.add(a);
                    }
                    if (!articleNos.isEmpty()) break;
                }
                if (articleNos.isEmpty()) {
                    articleNos.add("");
                }
            }

            @SuppressWarnings("unchecked")
            final Map<String, Map<String, String>>[] currentRowsByArticle = (Map<String, Map<String, String>>[]) new Map[]{new LinkedHashMap<>()};
            final boolean[] sawAnySize = new boolean[]{false};

            for (int i = idxHeader; i < texts.size(); i++) {
                String t = oneLine(texts.get(i));
                if (t.isBlank()) continue;

                String lower = t.toLowerCase(Locale.ROOT);
                // Log all lines to trace processing order
                String curType = "null";
                if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                    try {
                        Map<String, String> anyRow = currentRowsByArticle[0].values().iterator().next();
                        String v = anyRow == null ? "" : anyRow.getOrDefault("type", "");
                        if (v != null && !v.isBlank()) curType = v;
                    } catch (Exception ignored) {}
                }
                log.info("[LINE-TRACE] page={} idx={} currentRowType={} line='{}'",
                        e.getKey(), i,
                        curType,
                        t.length() > 100 ? t.substring(0, 100) + "..." : t);
                if (lower.startsWith("bill of material")) break;

                if (lower.equals("assortment") || lower.startsWith("assortment ") ||
                        lower.equals("solid") || lower.startsWith("solid ") ||
                        lower.equals("total") || lower.startsWith("total ")) {
                    if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                        for (Map<String, String> r : currentRowsByArticle[0].values()) {
                            if (r == null) continue;
                            if (sawAnySize[0] || r.containsKey("total") || r.containsKey("type")) {
                                ensureSizeDefaults(r);
                                out.add(r);
                            }
                        }
                    }
                    String type = lower.startsWith("assortment") ? "Assortment" : (lower.startsWith("solid") ? "Solid" : "Total");
                    currentRowsByArticle[0] = new LinkedHashMap<>();
                    sawAnySize[0] = false;
                    for (String a : articleNos) {
                        Map<String, String> row = new LinkedHashMap<>();
                        if (!type.isBlank()) row.put("type", type);
                        if (!color.isBlank()) row.put("color", color);
                        if (!destinationCountry.isBlank()) {
                            row.put("destinationCountry", destinationCountry);
                            row.put("countryOfDestination", destinationCountry);
                        }
                        if (a != null && !a.isBlank()) {
                            row.put("articleNo", a);
                        }
                        currentRowsByArticle[0].put(nvl(a), row);
                    }
                    continue;
                }

                // Capture 'No of Asst:' value — must run even when currentRow is null
                // so that the backfill path can attach it to the last emitted Assortment row.
                if (lower.startsWith("no of asst")) {
                    String tail = "";
                    Matcher m = Pattern.compile("(\\d[\\d\\s]*?)\\s*$").matcher(t);
                    if (m.find()) tail = nvl(m.group(1));
                    List<String> vals = splitMultiArticleNumbers(tail, articleNos.size());
                    if (vals.isEmpty()) {
                        String n = parseInlineOrNextNumber(t, texts, i + 1);
                        vals = splitMultiArticleNumbers(n, articleNos.size());
                    }
                    if (!vals.isEmpty()) {
                        if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                            if (vals.size() == articleNos.size() && articleNos.size() > 1) {
                                for (int ai = 0; ai < articleNos.size(); ai++) {
                                    Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                    if (r != null) r.put("noOfAsst", vals.get(ai));
                                }
                            } else {
                                String v = vals.get(0);
                                for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                    if (r != null) r.put("noOfAsst", v);
                                }
                            }
                        } else {
                            // If the Assortment row was already emitted (e.g., upon 'Quantity:'),
                            // backfill into the last Assortment row for this page.
                            String v = vals.get(0);
                            for (int ri = out.size() - 1; ri >= pageOutStart; ri--) {
                                Map<String, String> r = out.get(ri);
                                if ("Assortment".equals(r.getOrDefault("type", ""))) {
                                    r.put("noOfAsst", v);
                                    break;
                                }
                            }
                        }
                    }
                    continue;
                }

                if (currentRowsByArticle[0] == null || currentRowsByArticle[0].isEmpty()) {
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
                    // Kids size backfill: handle "0-1M (50)* 6", "1½-2Y (92)* 68" etc. after Quantity reset
                    Matcher km2 = SIZE_VALUE_LINE_KIDS.matcher(t);
                    if (km2.matches()) {
                        String kidsKey = normalizeKidsSizeKey(km2.group(1), km2.group(2));
                        String kv = normalizeNumber(km2.group(3));
                        if (kidsKey != null && !kv.isBlank() && out.size() > pageOutStart) {
                            for (int ri = out.size() - 1; ri >= pageOutStart; ri--) {
                                Map<String, String> r = out.get(ri);
                                String existing = r.get(kidsKey);
                                if (existing == null || existing.isBlank() || "0".equals(existing)) {
                                    log.info("[LINE-BACKFILL] KIDS size={} value={} into row[{}] type={}", kidsKey, kv, ri, r.get("type"));
                                    r.put(kidsKey, kv);
                                }
                            }
                        }
                    }
                    // Numeric cm size backfill: handle "50 (50)* 35", "86 (86)* 82" etc. after Quantity reset
                    Matcher ncm2 = SIZE_VALUE_LINE_NUMERIC_CM.matcher(t);
                    if (ncm2.matches()) {
                        String label2 = ncm2.group(1).trim();
                        String cm2 = ncm2.group(2).trim().replaceAll("[^0-9]", "");
                        String numKey2 = label2 + "(" + cm2 + ")";
                        String nv2 = normalizeNumber(ncm2.group(3));
                        if (!nv2.isBlank() && out.size() > pageOutStart) {
                            for (int ri = out.size() - 1; ri >= pageOutStart; ri--) {
                                Map<String, String> r = out.get(ri);
                                String existing = r.get(numKey2);
                                if (existing == null || existing.isBlank() || "0".equals(existing)) {
                                    log.info("[LINE-BACKFILL] NUMERIC_CM size={} value={} into row[{}] type={}", numKey2, nv2, ri, r.get("type"));
                                    r.put(numKey2, nv2);
                                }
                            }
                        }
                    }
                    // Universal fallback backfill: "0M (50)* 2", "1½ (86)* 8", any other format
                    Matcher ua2 = SIZE_VALUE_LINE_ANY.matcher(t);
                    if (ua2.matches()) {
                        String anyKey2 = normalizeGenericSizeKey(ua2.group(1), ua2.group(2));
                        String av2 = normalizeNumber(ua2.group(3));
                        if (anyKey2 != null && !av2.isBlank() && out.size() > pageOutStart) {
                            for (int ri = out.size() - 1; ri >= pageOutStart; ri--) {
                                Map<String, String> r = out.get(ri);
                                String existing = r.get(anyKey2);
                                if (existing == null || existing.isBlank() || "0".equals(existing)) {
                                    log.info("[LINE-BACKFILL] ANY size={} value={} into row[{}] type={}", anyKey2, av2, ri, r.get("type"));
                                    r.put(anyKey2, av2);
                                }
                            }
                        }
                    }
                    continue;
                }

                Matcher qm = QUANTITY_LINE.matcher(t);
                if (qm.matches()) {
                    List<String> vals = splitMultiArticleNumbers(qm.group(1), articleNos.size());

                    if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                        if (vals.size() == articleNos.size() && articleNos.size() > 1) {
                            for (int ai = 0; ai < articleNos.size(); ai++) {
                                Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                if (r != null) r.put("total", vals.get(ai));
                            }
                        } else if (!vals.isEmpty()) {
                            String v = vals.get(0);
                            for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                if (r != null) r.put("total", v);
                            }
                        }
                        for (Map<String, String> r : currentRowsByArticle[0].values()) {
                            if (r == null) continue;
                            ensureSizeDefaults(r);
                            out.add(r);
                        }
                    }
                    currentRowsByArticle[0] = new LinkedHashMap<>();
                    sawAnySize[0] = false;
                    continue;
                }

                // Fallback: bare "Quantity:" without a parseable number — still emit the row
                if (lower.startsWith("quantity") || lower.startsWith("qty")) {
                    if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                        for (Map<String, String> r : currentRowsByArticle[0].values()) {
                            if (r == null) continue;
                            ensureSizeDefaults(r);
                            out.add(r);
                        }
                    }
                    currentRowsByArticle[0] = new LinkedHashMap<>();
                    sawAnySize[0] = false;
                    continue;
                }

                // H&M kids/baby lines like "0-1M (50)* 16", "1½-2Y (92)* 68".
                // Run BEFORE the adult patterns because adult regexes require
                // a leading letter and won't match these — but we want a
                // dedicated path so the kids age-range becomes a real size key
                // on the current row instead of being silently dropped (which
                // would leave the row with no sizes and trip ensureSizeDefaults
                // into seeding misleading XS/S/M/L/XL=0 placeholders).
                Matcher km = SIZE_VALUE_LINE_KIDS.matcher(t);
                if (km.matches()) {
                    String kidsKey = normalizeKidsSizeKey(km.group(1), km.group(2));
                    List<String> kvals = splitMultiArticleNumbers(km.group(3), articleNos.size());
                    if (kidsKey != null
                            && currentRowsByArticle[0] != null
                            && !currentRowsByArticle[0].isEmpty()
                            && !kvals.isEmpty()) {
                        if (kvals.size() == articleNos.size() && articleNos.size() > 1) {
                            for (int ai = 0; ai < articleNos.size(); ai++) {
                                Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                if (r != null) r.put(kidsKey, kvals.get(ai));
                            }
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_KIDS multi matched: line='{}' -> size={} valuesCount={}",
                                    t, kidsKey, kvals.size());
                        } else {
                            String kv = kvals.get(0);
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_KIDS matched: line='{}' -> size={} value={}", t, kidsKey, kv);
                            for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                if (r != null) r.put(kidsKey, kv);
                            }
                        }
                        sawAnySize[0] = true;
                        continue;
                    }
                }

                // Numeric-only cm sizes: "50 (50)* 35", "86 (86)* 82" (Italy/Spain, Australia pages)
                Matcher ncm = SIZE_VALUE_LINE_NUMERIC_CM.matcher(t);
                if (ncm.matches()) {
                    String label = ncm.group(1).trim();
                    String cm = ncm.group(2).trim().replaceAll("[^0-9]", "");
                    String numKey = label + "(" + cm + ")";
                    List<String> nvals = splitMultiArticleNumbers(ncm.group(3), articleNos.size());
                    if (!nvals.isEmpty()
                            && currentRowsByArticle[0] != null
                            && !currentRowsByArticle[0].isEmpty()) {
                        if (nvals.size() == articleNos.size() && articleNos.size() > 1) {
                            for (int ai = 0; ai < articleNos.size(); ai++) {
                                Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                if (r != null) r.put(numKey, nvals.get(ai));
                            }
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_NUMERIC_CM multi matched: line='{}' -> size={} valuesCount={}", t, numKey, nvals.size());
                        } else {
                            String nv = nvals.get(0);
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_NUMERIC_CM matched: line='{}' -> size={} value={}", t, numKey, nv);
                            for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                if (r != null) r.put(numKey, nv);
                            }
                        }
                        sawAnySize[0] = true;
                        continue;
                    }
                }

                // Lines like: "XS (XS)* 236", "L(L)y* 622"
                Matcher sm = SIZE_VALUE_LINE.matcher(t);
                if (sm.matches()) {
                    String sizeRaw = sm.group(1);
                    String size = normalizeSizeKey(sizeRaw);
                    if (size == null) {
                        Matcher pm = Pattern.compile("\\(\\s*([A-Za-z]{1,2})\\s*(?:\\/\\s*P)?\\s*\\)").matcher(t);
                        if (pm.find()) {
                            String inside = pm.group(1);
                            size = normalizeSizeKey(inside);
                        }
                    }
                    List<String> vals = splitMultiArticleNumbers(sm.group(2), articleNos.size());

                    if (size != null && currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty() && !vals.isEmpty()) {
                        if (vals.size() == articleNos.size() && articleNos.size() > 1) {
                            for (int ai = 0; ai < articleNos.size(); ai++) {
                                Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                if (r != null) r.put(size, vals.get(ai));
                            }
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE multi matched: line='{}' -> size={} valuesCount={}", t, size, vals.size());
                        } else {
                            String v = vals.get(0);
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE matched: line='{}' -> size={} value={}", t, size, v);
                            for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                if (r != null) r.put(size, v);
                            }
                        }
                        sawAnySize[0] = true;
                    }
                    continue;
                }

                // Some countries use numeric size labels at the start, e.g. "155/80A (XS/P)* 30"
                Matcher sp = SIZE_VALUE_LINE_PARENS.matcher(t);
                if (sp.matches()) {
                    String inside = sp.group(1);
                    String size = normalizeSizeKey(inside);
                    List<String> vals = splitMultiArticleNumbers(sp.group(2), articleNos.size());

                    if (size != null && currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty() && !vals.isEmpty()) {
                        if (vals.size() == articleNos.size() && articleNos.size() > 1) {
                            for (int ai = 0; ai < articleNos.size(); ai++) {
                                Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                if (r != null) r.put(size, vals.get(ai));
                            }
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_PARENS multi matched: line='{}' -> size={} valuesCount={}", t, size, vals.size());
                        } else {
                            String v = vals.get(0);
                            log.info("[SIZE-PARSE] SIZE_VALUE_LINE_PARENS matched: line='{}' -> size={} value={}", t, size, v);
                            for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                if (r != null) r.put(size, v);
                            }
                        }
                        sawAnySize[0] = true;
                    }
                } else {
                    // Third fallback: flexible pattern for size lines that don't match strict patterns
                    Matcher sf = SIZE_VALUE_LINE_FLEX.matcher(t);
                    if (sf.find()) {
                        String sizeRaw = sf.group(1);
                        String size = normalizeSizeKey(sizeRaw);
                        List<String> vals = splitMultiArticleNumbers(sf.group(2), articleNos.size());

                        if (size != null && currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty() && !vals.isEmpty()) {
                            if (vals.size() == articleNos.size() && articleNos.size() > 1) {
                                for (int ai = 0; ai < articleNos.size(); ai++) {
                                    Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                    if (r != null) r.put(size, vals.get(ai));
                                }
                                log.info("[SIZE-PARSE] SIZE_VALUE_LINE_FLEX multi matched: line='{}' -> size={} valuesCount={}", t, size, vals.size());
                            } else {
                                String v = vals.get(0);
                                log.info("[SIZE-PARSE] SIZE_VALUE_LINE_FLEX matched: line='{}' -> size={} value={}", t, size, v);
                                for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                    if (r != null) r.put(size, v);
                                }
                            }
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
                                for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                    if (r != null) r.put(size, v);
                                }
                                sawAnySize[0] = true;
                            }
                        } else {
                            // Fifth fallback: universal pattern — any "label (value)* qty" line in valid section
                            Matcher ua = SIZE_VALUE_LINE_ANY.matcher(t);
                            if (ua.matches() && currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                                String anyKey = normalizeGenericSizeKey(ua.group(1), ua.group(2));
                                List<String> avals = splitMultiArticleNumbers(ua.group(3), articleNos.size());
                                if (anyKey != null && !avals.isEmpty()) {
                                    if (avals.size() == articleNos.size() && articleNos.size() > 1) {
                                        for (int ai = 0; ai < articleNos.size(); ai++) {
                                            Map<String, String> r = currentRowsByArticle[0].get(nvl(articleNos.get(ai)));
                                            if (r != null) r.put(anyKey, avals.get(ai));
                                        }
                                        log.info("[SIZE-PARSE] SIZE_VALUE_LINE_ANY multi matched: line='{}' -> size={} valuesCount={}", t, anyKey, avals.size());
                                    } else {
                                        String av = avals.get(0);
                                        log.info("[SIZE-PARSE] SIZE_VALUE_LINE_ANY matched: line='{}' -> size={} value={}", t, anyKey, av);
                                        for (Map<String, String> r : currentRowsByArticle[0].values()) {
                                            if (r != null) r.put(anyKey, av);
                                        }
                                    }
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
            }

            if (currentRowsByArticle[0] != null && !currentRowsByArticle[0].isEmpty()) {
                for (Map<String, String> r : currentRowsByArticle[0].values()) {
                    if (r == null) continue;
                    if (sawAnySize[0] || r.containsKey("total") || r.containsKey("type")) {
                        ensureSizeDefaults(r);
                        out.add(r);
                    }
                }
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

        Map<String, Map<String, String>> assortmentByArticle = new LinkedHashMap<>();
        Map<String, Map<String, String>> solidByArticle = new LinkedHashMap<>();
        Map<String, Map<String, String>> totalByArticle = new LinkedHashMap<>();
        for (int i = startIdx; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            String articleNo = r.getOrDefault("articleNo", "");
            String type = r.getOrDefault("type", "");
            if ("Assortment".equals(type) && !assortmentByArticle.containsKey(articleNo)) assortmentByArticle.put(articleNo, r);
            else if ("Solid".equals(type) && !solidByArticle.containsKey(articleNo)) solidByArticle.put(articleNo, r);
            else if ("Total".equals(type) && !totalByArticle.containsKey(articleNo)) totalByArticle.put(articleNo, r);
        }

        List<String> sizes = List.of("XS", "S", "M", "L", "XL");

        Set<String> articles = new LinkedHashSet<>();
        articles.addAll(assortmentByArticle.keySet());
        articles.addAll(solidByArticle.keySet());
        articles.addAll(totalByArticle.keySet());

        for (String articleNo : articles) {
            Map<String, String> assortment = assortmentByArticle.get(articleNo);
            Map<String, String> solid = solidByArticle.get(articleNo);
            Map<String, String> total = totalByArticle.get(articleNo);
            if (solid == null || total == null) continue;

            int solidTotal;
            try {
                solidTotal = Integer.parseInt(solid.getOrDefault("total", "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            int solidSum = 0;
            for (String sz : sizes) {
                try {
                    solidSum += Integer.parseInt(solid.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                } catch (NumberFormatException ignored) {}
            }
            if (solidTotal > 0 && solidSum == solidTotal) continue;

        // (2) Only trust Total if its per-size values sum equals its "total" field.
            int totalExpected;
            try {
                totalExpected = Integer.parseInt(total.getOrDefault("total", "0").replaceAll("\\s+", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (totalExpected <= 0) continue;

            int totalSum = 0;
            for (String sz : sizes) {
                try {
                    totalSum += Integer.parseInt(total.getOrDefault(sz, "0").replaceAll("\\s+", ""));
                } catch (NumberFormatException ignored) {}
            }
            if (totalSum != totalExpected) continue;

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
                    log.info("[SOLID-RECONCILE] size={} old={} new={} (total={} - assortment={}*noOfAsst={}) country={} articleNo={}",
                            sz, solidVal, expectedSolid, totVal, assortVal, noOfAsst,
                            solid.getOrDefault("destinationCountry", ""), articleNo);
                    solid.put(sz, String.valueOf(expectedSolid));
                    changed = true;
                }
                newSolidSum += expectedSolid;
            }

            if (changed) {
                solid.put("total", String.valueOf(newSolidSum));
            }
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
        Map<String, Map<String, String>> assortmentByArticle = new LinkedHashMap<>();
        Map<String, Map<String, String>> solidByArticle = new LinkedHashMap<>();
        Map<String, Map<String, String>> totalByArticle = new LinkedHashMap<>();
        for (int i = startIdx; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            String articleNo = r.getOrDefault("articleNo", "");
            String type = r.getOrDefault("type", "");
            if ("Assortment".equals(type) && !assortmentByArticle.containsKey(articleNo)) assortmentByArticle.put(articleNo, r);
            else if ("Solid".equals(type) && !solidByArticle.containsKey(articleNo)) solidByArticle.put(articleNo, r);
            else if ("Total".equals(type) && !totalByArticle.containsKey(articleNo)) totalByArticle.put(articleNo, r);
        }

        Set<String> articles = new LinkedHashSet<>();
        articles.addAll(assortmentByArticle.keySet());
        articles.addAll(solidByArticle.keySet());
        articles.addAll(totalByArticle.keySet());
        if (articles.isEmpty()) return;

        List<String> sizes = List.of("XS", "S", "M", "L", "XL");

        for (String articleNo : articles) {
            Map<String, String> assortment = assortmentByArticle.get(articleNo);
            Map<String, String> solid = solidByArticle.get(articleNo);
            Map<String, String> total = totalByArticle.get(articleNo);
            if (assortment == null || solid == null || total == null) continue;

            for (String sz : sizes) {
                String v = assortment.get(sz);
                if (v != null && !v.isBlank()) {
                    try {
                        int iv = Integer.parseInt(v.replaceAll("\\s+", ""));
                        if (iv > 0) {
                            assortment = null;
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                        assortment = null;
                        break;
                    }
                }
            }
            if (assortment == null) continue;

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
            if (gcd <= 0) continue;

            int divisor = gcd;
            if (qtyAssort != null && qtyAssort > 0) {
                boolean allDivisible = true;
                for (int d : diffs) {
                    if (d % qtyAssort != 0) {
                        allDivisible = false;
                        break;
                    }
                }
                if (allDivisible) {
                    int sumIfQty = 0;
                    for (int d : diffs) sumIfQty += d / qtyAssort;
                    if (sumIfQty == qtyAssort) {
                        divisor = qtyAssort;
                    } else {
                        divisor = gcd;
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

    private static List<String> splitMultiArticleNumbers(String raw, int expectedCount) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.isBlank()) return out;

        if (expectedCount <= 1) {
            String v = normalizeNumberToken(s);
            if (!v.isBlank()) out.add(v);
            return out;
        }

        String[] toks = s.split("\\s+");
        if (toks.length == expectedCount) {
            for (String t : toks) {
                String v = normalizeNumberToken(t);
                if (!v.isBlank()) out.add(v);
            }
            return out;
        }

        // General-case grouping for layouts that merge numbers with spaces, e.g.
        // expectedCount=3 and raw="1 640 1592 925" should become [1640, 1592, 925]
        // (instead of normalizeNumberToken => 16401592925).
        {
            String digitsOnly = s.replaceAll("[^0-9]", " ").replaceAll("\\s+", " ").trim();
            if (!digitsOnly.isBlank()) {
                String[] dtoks = digitsOnly.split(" ");
                List<String> groups = new ArrayList<>();
                StringBuilder cur = new StringBuilder();
                for (String t : dtoks) {
                    if (t == null || t.isBlank()) continue;
                    boolean groupStarted = cur.length() > 0;
                    boolean isThousandChunk = t.length() == 3;
                    if (!groupStarted) {
                        cur.append(t);
                    } else if (isThousandChunk && cur.length() <= 3) {
                        // Treat 3-digit tokens as a continuation of the previous number (thousand separator)
                        cur.append(t);
                    } else {
                        groups.add(cur.toString());
                        cur.setLength(0);
                        cur.append(t);
                    }
                }
                if (cur.length() > 0) groups.add(cur.toString());

                if (groups.size() == expectedCount) {
                    out.addAll(groups);
                    return out;
                }
                if (groups.size() > expectedCount && expectedCount > 1) {
                    // If there are extra groups, take the tail (often the right-most values are per-article)
                    out.addAll(groups.subList(groups.size() - expectedCount, groups.size()));
                    return out;
                }
            }
        }

        // Handle thousand-separated numbers like: "2 578 2 496 1 385" for 3 articles.
        if (toks.length == expectedCount * 2) {
            for (int i = 0; i < expectedCount; i++) {
                String joined = toks[i * 2] + " " + toks[i * 2 + 1];
                String v = normalizeNumberToken(joined);
                if (!v.isBlank()) out.add(v);
            }
            return out;
        }

        // Fallback: treat as a single number.
        String v = normalizeNumberToken(s);
        if (!v.isBlank()) out.add(v);
        return out;
    }

    /**
     * Meta keys that exist on a Section-2 row but are NOT size-quantity columns.
     * Used by {@link #ensureSizeDefaults} to detect non-adult size layouts (kids /
     * baby) and by total computation to know which row entries to sum.
     */
    private static final Set<String> SECTION2_META_KEYS = Set.of(
            "type", "color", "colour", "articleno", "article",
            "destinationcountry", "countryofdestination", "noofasst",
            "total", "size", "qty");

    private static void ensureSizeDefaults(Map<String, String> row) {
        if (row == null) return;
        String type = row.get("type");
        if (type == null) return;
        if (!("Assortment".equals(type) || "Solid".equals(type) || "Total".equals(type))) return;

        List<String> adultSizes = List.of("XS", "S", "M", "L", "XL");

        // Detect whether this row already carries non-adult size keys (e.g. kids
        // age-range labels like "0-1M(50)" or "1½-2Y(92)"). When it does, we must
        // NOT seed XS/S/M/L/XL=0 placeholders — that would force the frontend to
        // render five empty adult rows alongside the real kids data and confuse
        // the user (this is exactly the "size belum berubah" symptom).
        boolean hasNonAdultSize = false;
        for (Map.Entry<String, String> e : row.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String low = k.toLowerCase(Locale.ROOT);
            if (SECTION2_META_KEYS.contains(low)) continue;
            if (adultSizes.contains(k)) continue;
            String v = e.getValue();
            if (v != null && !v.isBlank()) { hasNonAdultSize = true; break; }
        }

        long sum = 0;
        boolean anyNumeric = false;

        if (hasNonAdultSize) {
            // Non-adult layout: sum every numeric non-meta key already on the row.
            for (Map.Entry<String, String> e : row.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                if (SECTION2_META_KEYS.contains(k.toLowerCase(Locale.ROOT))) continue;
                String v = e.getValue();
                if (v == null || v.isBlank()) continue;
                try {
                    sum += Integer.parseInt(v.replaceAll("\\s+", ""));
                    anyNumeric = true;
                } catch (NumberFormatException ignored) {}
            }
        } else {
            // Adult layout: fill any missing XS/S/M/L/XL with "0" so downstream
            // reconciliation (Total = Solid + Assortment*NoOfAsst) has a complete
            // grid to work against.
            for (String sz : adultSizes) {
                String v = row.get(sz);
                if (v == null || v.isBlank()) {
                    row.put(sz, "0");
                    v = "0";
                }
                try {
                    sum += Integer.parseInt(v.replaceAll("\\s+", ""));
                    anyNumeric = true;
                } catch (NumberFormatException ignored) {}
            }
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

    /**
     * Build the canonical map key used to store a kids/baby size value on a row.
     * Joins the age-range and cm parts so the resulting key has no whitespace
     * (e.g. {@code "0-1M(50)"}, {@code "1½-2Y(92)"}). Returns {@code null} if
     * the age-range piece doesn't match {@link #KIDS_AGE_RANGE_PAT}; the cm
     * piece is optional and gets stripped of non-digits before being appended.
     *
     * @param ageRange raw age-range token, e.g. "0-1M", "1½-2Y"
     * @param cm raw cm token (digits only), e.g. "50", "92" — may be null
     */
    /**
     * Normalize any generic size label + parenthetical value into a stable key.
     * Returns a normalized letter-size key (e.g. "XS") for adult sizes,
     * or "LABEL(VALUE)" for kids/baby/numeric sizes.
     * Returns null if the label looks like a metadata field.
     */
    private static String normalizeGenericSizeKey(String label, String value) {
        if (label == null) return null;
        String lbl = label.trim().toUpperCase(Locale.ROOT).replaceAll("\\*+$", "").trim().replaceAll("\\s+", "");
        String val = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\u00BD\\-/]", "");
        if (lbl.isEmpty()) return null;
        if (lbl.contains(":") || lbl.contains("=")) return null;
        String[] metaStarts = {"NO", "ARTICLE", "ORDER", "PRODUCT", "COLOUR", "COLOR", "OPTION", "SEASON", "PAGE", "VERSION"};
        for (String ms : metaStarts) { if (lbl.startsWith(ms)) return null; }
        String norm = normalizeSizeKey(lbl);
        if (norm != null) return norm;
        return val.isEmpty() ? lbl : lbl + "(" + val + ")";
    }

    private static String normalizeKidsSizeKey(String ageRange, String cm) {
        if (ageRange == null) return null;
        String ar = ageRange.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("\\*+", "");
        if (!KIDS_AGE_RANGE_PAT.matcher(ar).matches()) return null;
        if (cm == null) return ar;
        String cn = cm.trim().replaceAll("[^0-9]", "");
        if (cn.isEmpty()) return ar;
        return ar + "(" + cn + ")";
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
                        int syntheticTop = 5000;
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
                            syntheticTop += 100;
                            OcrNewLine suppl = OcrNewLine.builder()
                                    .page(pageNum)
                                    .text(trimmed)
                                    .left(0).top(syntheticTop).right(1000).bottom(syntheticTop + 20)
                                    .confidence(99f)
                                    .words(List.of())
                                    .build();
                            allLines.add(suppl);
                            rawLinesForDetail.add(suppl);
                            if (rawLinesForTables != null && rawLinesForTables != allLines) {
                                rawLinesForTables.add(suppl);
                            }
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

            if (isPdf(file)) {
                Map<String, String> pdfHeaderFields = extractPurchaseOrderHeaderFieldsFromPdfBytes(fileBytes);
                if (pdfHeaderFields != null && !pdfHeaderFields.isEmpty()) {
                    for (Map.Entry<String, String> en : pdfHeaderFields.entrySet()) {
                        String k = en.getKey();
                        String v = nvl(en.getValue()).trim();
                        if (k == null || k.isBlank() || v.isBlank()) continue;
                        String cur = nvl(formFields.get(k)).trim();
                        if (cur.isBlank()) {
                            formFields.put(k, v);
                        }
                    }
                }
            }

            // Split values that contain right-column labels embedded in them
            // (PDFBox text layer reports two-column rows as one merged line).
            splitMultiColumnHeaderValues(formFields);

            enrichDeliveryFields(formFields, allLines);
            enrichPurchaseOrderHeaderFields(formFields, allLines);
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

            // Enrich the detail rows so Section 2C shows the PDF-visible article label
            // (Article No + H&M Colour Code), e.g. "001 22-216".
            Map<String, String> articleLabelByNo = buildArticleLabelByNoFromDetail(salesOrderDetailSizeBreakdown);
            Map<String, String> articleLabelByNoFromTables = buildArticleLabelByNoFromTables(tables);
            Map<String, String> colourNameByArticleNoFromTables = buildArticleColourNameByNoFromTables(tables);
            Map<String, String> colourNameByArticleNoFromLines = buildArticleColourNameByNoFromLines(allLines);

            if (articleLabelByNo.isEmpty()) {
                articleLabelByNo = buildArticleLabelByNoFromHeaderOrLines(formFields, allLines);
                for (Map.Entry<String, String> en : articleLabelByNoFromTables.entrySet()) {
                    articleLabelByNo.putIfAbsent(en.getKey(), en.getValue());
                }
            } else {
                // Fill missing mappings from header when some articles are absent from detail rows.
                Map<String, String> hdr = buildArticleLabelByNoFromHeaderOrLines(formFields, allLines);
                for (Map.Entry<String, String> en : hdr.entrySet()) {
                    articleLabelByNo.putIfAbsent(en.getKey(), en.getValue());
                }
                for (Map.Entry<String, String> en : articleLabelByNoFromTables.entrySet()) {
                    articleLabelByNo.putIfAbsent(en.getKey(), en.getValue());
                }
            }

            Map<String, String> hmColourCodeByArticleNoFromTables = new LinkedHashMap<>();
            if (articleLabelByNoFromTables != null && !articleLabelByNoFromTables.isEmpty()) {
                Pattern cPat = Pattern.compile("\\b(\\d{2}-\\d{3})\\b");
                for (Map.Entry<String, String> en : articleLabelByNoFromTables.entrySet()) {
                    String a3 = nvl(en.getKey()).trim();
                    String label = nvl(en.getValue()).trim();
                    if (a3.isBlank() || label.isBlank()) continue;
                    Matcher m = cPat.matcher(label);
                    if (m.find()) hmColourCodeByArticleNoFromTables.putIfAbsent(a3, m.group(1));
                }
            }
            Map<String, String> colourNameByArticleNoMerged = new LinkedHashMap<>();
            if (colourNameByArticleNoFromTables != null && !colourNameByArticleNoFromTables.isEmpty()) {
                colourNameByArticleNoMerged.putAll(colourNameByArticleNoFromTables);
            }
            if (colourNameByArticleNoFromLines != null && !colourNameByArticleNoFromLines.isEmpty()) {
                for (Map.Entry<String, String> en : colourNameByArticleNoFromLines.entrySet()) {
                    String a3 = nvl(en.getKey()).trim();
                    String v = nvl(en.getValue()).trim();
                    if (a3.isBlank() || v.isBlank()) continue;
                    // Prefer the line-based value when present, as it can contain wrapped long text.
                    colourNameByArticleNoMerged.put(a3, v);
                }
            }

            fillDetailRowColourFieldsFromArticleMaps(salesOrderDetailSizeBreakdown, hmColourCodeByArticleNoFromTables, colourNameByArticleNoMerged);

            if (!articleLabelByNo.isEmpty()) {
                applyArticleLabelsToDetailRows(salesOrderDetailSizeBreakdown, articleLabelByNo);
            }

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

            // If the CSB extractor only captured the 3-digit article number (e.g. "001"),
            // enrich it using the header fields which usually contain both:
            //   Article No: 001
            //   H&M Colour Code: 22-216
            // so the UI can show the PDF-visible label "001 22-216".
            fillColourSizeBreakdownArticleLabelsFromHeader(colourSizeBreakdown, formFields);
            fillColourSizeBreakdownArticleLabelsFromHeaderOrLines(colourSizeBreakdown, formFields, allLines);

            // Align Section 2B article column keys with the PDF-visible article label used by Section 2C
            // (e.g. "001 22-216"). This makes All Scan and SizePerColour scans consistent.
            normalizeTotalCountryBreakdownArticleKeys(totalCountryBreakdown, colourSizeBreakdown, articleLabelByNo);

            // ── Purchase Order specific extractions ────────────────────────────
            boolean poTodFromPdfText = false;
            List<Map<String, String>> poTimeOfDelivery = List.of();
            if (isPdf(file)) {
                poTimeOfDelivery = extractPurchaseOrderTimeOfDeliveryFromPdfBytes(fileBytes, fname);
                poTodFromPdfText = poTimeOfDelivery != null && !poTimeOfDelivery.isEmpty();
            }
            if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) {
                poTimeOfDelivery = extractPurchaseOrderTimeOfDelivery(allLines);
            }
            // Only run fill-missing heuristics when the main extractor was OCR-based.
            if (!poTodFromPdfText && isPdf(file) && poTimeOfDelivery != null && !poTimeOfDelivery.isEmpty() && pageImages != null && !pageImages.isEmpty()) {
                fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromRegionOcr(poTimeOfDelivery, allLines, pageImages.get(0), ocrEngine, fname);
            }
            if (!poTodFromPdfText && isPdf(file) && poTimeOfDelivery != null && !poTimeOfDelivery.isEmpty()) {
                fillMissingPurchaseOrderTimeOfDeliveryPlanningMarketsFromPdfBytes(poTimeOfDelivery, fileBytes, fname);
            }

            if (isPdf(file)) {
                String pdfTermsPage1 = extractPurchaseOrderTermsOfDeliveryPage1FromPdfBytes(fileBytes);
                if (pdfTermsPage1 != null && !pdfTermsPage1.isBlank()) {
                    formFields.put("Terms of Delivery", pdfTermsPage1);
                }
            }
            List<Map<String, String>> poTermsOfDeliveryByPage = extractPurchaseOrderTermsOfDeliveryByPage(allLines);
            if (isPdf(file)) {
                List<Map<String, String>> pdfTermsByPage = extractPurchaseOrderTermsOfDeliveryByPageFromPdfBytes(fileBytes);
                if (pdfTermsByPage != null && !pdfTermsByPage.isEmpty()) {
                    if (poTermsOfDeliveryByPage == null) poTermsOfDeliveryByPage = new ArrayList<>();
                    Map<String, Map<String, String>> byPage = new LinkedHashMap<>();
                    for (Map<String, String> r : poTermsOfDeliveryByPage) {
                        if (r == null) continue;
                        String p = nvl(r.get("page")).trim();
                        if (p.isBlank()) p = "1";
                        byPage.put(p, r);
                    }
                    for (Map<String, String> r : pdfTermsByPage) {
                        if (r == null) continue;
                        String p = nvl(r.get("page")).trim();
                        if (p.isBlank()) p = "1";
                        if (p.equals("1")) continue;
                        String terms = nvl(r.get("termsOfDelivery")).trim();
                        if (terms.isBlank()) continue;
                        Map<String, String> cur = byPage.get(p);
                        if (cur == null) {
                            Map<String, String> nr = new LinkedHashMap<>();
                            nr.put("page", p);
                            nr.put("termsOfDelivery", terms);
                            nr.put("_src", "pdfText");
                            poTermsOfDeliveryByPage.add(nr);
                            byPage.put(p, nr);
                        } else {
                            cur.put("termsOfDelivery", terms);
                            cur.put("_src", "pdfText");
                        }
                    }
                }
            }
            Set<String> allowedTermsCountries = extractTwoLetterCountryCodesFromTermsFirstLine(formFields.get("Terms of Delivery"));
            fillMissingPurchaseOrderTermsOfDeliveryCountryCodeFromRegionOcr(poTermsOfDeliveryByPage, allLines, pageImages, ocrEngine, fname, effectiveDebug, allowedTermsCountries);

            List<Map<String, String>> poQuantityPerArticle = extractPurchaseOrderQuantityPerArticleByPage(allLines);
            // Pages > 1 often lose this dense table in hOCR; supplement with PDFBox text extraction when PDF.
            if (isPdf(file)) {
                List<Map<String, String>> poQuantityFromPdf = extractPurchaseOrderQuantityPerArticleFromPdfBytes(fileBytes);
                if (poQuantityFromPdf != null && !poQuantityFromPdf.isEmpty()) {
                    if (poQuantityPerArticle == null) poQuantityPerArticle = new ArrayList<>();

                    // Header/awal table (page 1) should prefer PDF text layer only (more accurate than OCR).
                    // If PDFBox has page=1 rows, replace any existing page-1 rows with PDFBox results.
                    List<Map<String, String>> pdfPage1 = new ArrayList<>();
                    for (Map<String, String> r : poQuantityFromPdf) {
                        if (r == null) continue;
                        if ("1".equals(nvl(r.get("page")).trim())) {
                            pdfPage1.add(r);
                        }
                    }
                    if (!pdfPage1.isEmpty()) {
                        List<Map<String, String>> keep = new ArrayList<>();
                        for (Map<String, String> r : poQuantityPerArticle) {
                            if (r == null) continue;
                            String p = nvl(r.get("page")).trim();
                            if (!"1".equals(p)) keep.add(r);
                        }
                        keep.addAll(pdfPage1);
                        poQuantityPerArticle = keep;
                    }

                    Set<String> existingKeys = new HashSet<>();
                    for (Map<String, String> r : poQuantityPerArticle) {
                        if (r == null) continue;
                        String k = (nvl(r.get("page")) + "|" + nvl(r.get("articleNo")) + "|" + nvl(r.get("hmColourCode")) + "|" + nvl(r.get("ptArticleNumber")) + "|" + nvl(r.get("graphicalAppearance")) + "|" + nvl(r.get("qtyArticle")));
                        existingKeys.add(k);
                    }
                    for (Map<String, String> r : poQuantityFromPdf) {
                        if (r == null) continue;
                        String k = (nvl(r.get("page")) + "|" + nvl(r.get("articleNo")) + "|" + nvl(r.get("hmColourCode")) + "|" + nvl(r.get("ptArticleNumber")) + "|" + nvl(r.get("graphicalAppearance")) + "|" + nvl(r.get("qtyArticle")));
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
            normalizePurchaseOrderQuantityPerArticleGraphicalAppearance(poQuantityPerArticle);

            // Fallback: derive from the already-parsed sales order detail size breakdown (per destination country)
            if (totalCountryBreakdown == null || totalCountryBreakdown.isEmpty()) {
                totalCountryBreakdown = deriveTotalCountryBreakdownFromSalesOrderDetail(salesOrderDetailSizeBreakdown, poQuantityPerArticle);
                if (effectiveDebug) {
                    log.info("[TCB][DERIVE] derivedRows={} detailRows={} qpaRows={}",
                            totalCountryBreakdown == null ? 0 : totalCountryBreakdown.size(),
                            salesOrderDetailSizeBreakdown == null ? 0 : salesOrderDetailSizeBreakdown.size(),
                            poQuantityPerArticle == null ? 0 : poQuantityPerArticle.size());
                    if (totalCountryBreakdown != null && !totalCountryBreakdown.isEmpty()) {
                        log.info("[TCB][DERIVE] firstRow={}", totalCountryBreakdown.get(0));
                    }
                }
            }

            List<String> poInvoiceAvgPriceTraceBuffer = effectiveDebug ? new ArrayList<>() : null;
            List<Map<String, String>> poInvoiceAvgPrice = null;

            // Prefer PDFBox native text for PDFs because Tesseract often loses the dense country list
            // on page 1 (resulting in a single country like 'JP' instead of the full comma-separated list).
            if (isPdf(file)) {
                poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPriceFromPdfBytes(fileBytes);
            }

            if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) {
                poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPrice(allLines);
            }
            if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) {
                poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPriceByPage(allLines);
            }
            if (poInvoiceAvgPrice == null || poInvoiceAvgPrice.isEmpty()) {
                poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPrice(allLines);
                annotatePurchaseOrderInvoiceAvgPricePages(poInvoiceAvgPrice, allLines);
            }

            if (effectiveDebug) {
                log.info("[PO-INVOICE-PRICE][PIPE] afterExtract rows={}", poInvoiceAvgPrice == null ? 0 : poInvoiceAvgPrice.size());
                if (poInvoiceAvgPrice != null) {
                    for (int i = 0; i < Math.min(30, poInvoiceAvgPrice.size()); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        log.info("[PO-INVOICE-PRICE][PIPE] afterExtract[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                nvl(r.get("invoiceAveragePrice")).trim(),
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }

                    for (int i = 0; i < poInvoiceAvgPrice.size(); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        String price = nvl(r.get("invoiceAveragePrice")).trim();
                        if (!price.equals("5.40 USD")) continue;
                        if (poInvoiceAvgPriceTraceBuffer != null) {
                            poInvoiceAvgPriceTraceBuffer.add("[PO-INVOICE-PRICE][TRACE] afterExtract[" + i
                                    + "] page=" + nvl(r.get("page")).trim()
                                    + " price=" + price
                                    + " country=" + nvl(r.get("country")).trim()
                                    + " src=" + nvl(r.get("_src")).trim());
                        }
                        log.info("[PO-INVOICE-PRICE][TRACE] afterExtract[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                price,
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }
                }
            }

            // Some PDFs repeat the "Invoice Average Price" table across pages. Page 1 can
            // contain the price but miss the country, while a later page captures the full
            // "price currency country" on the same line. Enrich missing countries by matching
            // on the exact price token.
            fillMissingPurchaseOrderInvoiceAvgPriceCountriesFromOtherPages(poInvoiceAvgPrice);

            if (effectiveDebug) {
                log.info("[PO-INVOICE-PRICE][PIPE] afterFillFromOtherPages rows={}", poInvoiceAvgPrice == null ? 0 : poInvoiceAvgPrice.size());
                if (poInvoiceAvgPrice != null) {
                    for (int i = 0; i < Math.min(30, poInvoiceAvgPrice.size()); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        log.info("[PO-INVOICE-PRICE][PIPE] afterFillFromOtherPages[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                nvl(r.get("invoiceAveragePrice")).trim(),
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }

                    for (int i = 0; i < poInvoiceAvgPrice.size(); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        String price = nvl(r.get("invoiceAveragePrice")).trim();
                        if (!price.equals("5.40 USD")) continue;
                        if (poInvoiceAvgPriceTraceBuffer != null) {
                            poInvoiceAvgPriceTraceBuffer.add("[PO-INVOICE-PRICE][TRACE] afterFillFromOtherPages[" + i
                                    + "] page=" + nvl(r.get("page")).trim()
                                    + " price=" + price
                                    + " country=" + nvl(r.get("country")).trim()
                                    + " src=" + nvl(r.get("_src")).trim());
                        }
                        log.info("[PO-INVOICE-PRICE][TRACE] afterFillFromOtherPages[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                price,
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }
                }
            }

            try {
                Pattern twoLetterCountryPat = Pattern.compile("^[A-Z]{2}$");
                String page1Code = "";
                if (allLines != null && !allLines.isEmpty()) {
                    List<OcrNewLine> page1Lines = new ArrayList<>();
                    for (OcrNewLine l : allLines) {
                        if (l == null || l.getText() == null) continue;
                        if (l.getPage() == 1) page1Lines.add(l);
                    }
                    page1Lines.sort(Comparator
                            .comparingInt(OcrNewLine::getTop)
                            .thenComparingInt(OcrNewLine::getLeft));

                    boolean hasStandaloneOi = false;
                    for (OcrNewLine l : allLines) {
                        if (l == null || l.getText() == null) continue;
                        String s = oneLine(l.getText()).trim();
                        if (s.equalsIgnoreCase("OI")) {
                            hasStandaloneOi = true;
                            break;
                        }
                    }

                    int headerIdx = -1;
                    for (int i = 0; i < page1Lines.size(); i++) {
                        String s = oneLine(page1Lines.get(i).getText()).trim();
                        if (s.equalsIgnoreCase("Terms of Delivery") || s.toLowerCase(Locale.ROOT).startsWith("terms of delivery")) {
                            headerIdx = i;
                            break;
                        }
                    }
                    if (headerIdx >= 0) {
                        for (int i = headerIdx + 1; i < Math.min(page1Lines.size(), headerIdx + 6); i++) {
                            String s = oneLine(page1Lines.get(i).getText()).trim().toUpperCase(Locale.ROOT);
                            if (s.isBlank()) continue;
                            if (twoLetterCountryPat.matcher(s).matches()) {
                                page1Code = s;
                                break;
                            }
                        }
                    }

                    // Some layouts don't have a single-code line under the header. In that case,
                    // take the most "pure" country list line from page 1 and preserve its ordering.
                    Pattern countryListLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
                    String page1CountryListLine = "";
                    for (OcrNewLine l : page1Lines) {
                        if (l == null || l.getText() == null) continue;
                        String s0 = oneLine(l.getText()).replaceAll("\\s+", " ").trim();
                        if (s0.isBlank()) continue;
                        String s = s0.toUpperCase(Locale.ROOT).replaceAll("\\s*,\\s*", ", ");
                        if (!countryListLinePat.matcher(s.replaceAll(", ", ",")).matches()) continue;
                        if (s.length() > page1CountryListLine.length()) page1CountryListLine = s;
                    }

                    if (!page1CountryListLine.isBlank() && hasStandaloneOi) {
                        // OCR often confuses OI as OL/Ol. If the document contains standalone OI,
                        // normalize OL -> OI inside the list.
                        List<String> toks = new ArrayList<>();
                        for (String t : page1CountryListLine.split("\\s*,\\s*")) {
                            if (t == null) continue;
                            String tt = t.trim().toUpperCase(Locale.ROOT);
                            if (tt.equals("OL")) tt = "OI";
                            if (!tt.isBlank()) toks.add(tt);
                        }
                        LinkedHashSet<String> uniq = new LinkedHashSet<>(toks);
                        page1CountryListLine = String.join(", ", uniq);
                    }

                    // Ensure the prefix includes any planning-market codes that appear in the
                    // Time-of-Delivery section but are missing from the pure list line (e.g. DR).
                    // Preserve the pure line order, inserting missing codes after the closest
                    // preceding code from the ToD order.
                    if (!page1CountryListLine.isBlank()) {
                        Pattern todCodePat = Pattern.compile("\\b([A-Z]{2})\\s*\\(");
                        LinkedHashSet<String> todOrdered = new LinkedHashSet<>();
                        for (OcrNewLine l : page1Lines) {
                            if (l == null || l.getText() == null) continue;
                            String s0 = oneLine(l.getText()).replaceAll("\\s+", " ").trim();
                            if (s0.isBlank()) continue;
                            // Only consider typical ToD lines containing dates and planning markets
                            String low0 = s0.toLowerCase(Locale.ROOT);
                            boolean looksLikeTod = low0.contains(",") && (low0.contains("nov") || low0.contains("dec")
                                    || low0.contains("jan") || low0.contains("feb") || low0.contains("mar")
                                    || low0.contains("apr") || low0.contains("may") || low0.contains("jun")
                                    || low0.contains("jul") || low0.contains("aug") || low0.contains("sep")
                                    || low0.contains("oct")) && s0.contains("(");
                            if (!looksLikeTod) continue;

                            Matcher m = todCodePat.matcher(s0.toUpperCase(Locale.ROOT));
                            while (m.find()) {
                                String c = m.group(1);
                                if (c == null) continue;
                                String cc = c.trim().toUpperCase(Locale.ROOT);
                                if (hasStandaloneOi && cc.equals("OL")) cc = "OI";
                                if (!cc.isBlank()) todOrdered.add(cc);
                            }
                        }

                        if (!todOrdered.isEmpty()) {
                            List<String> base = new ArrayList<>();
                            for (String t : page1CountryListLine.split("\\s*,\\s*")) {
                                if (t == null) continue;
                                String tt = t.trim().toUpperCase(Locale.ROOT);
                                if (!tt.isBlank() && !base.contains(tt)) base.add(tt);
                            }
                            for (String c : todOrdered) {
                                if (c == null || c.isBlank()) continue;
                                if (base.contains(c)) continue;

                                int insertAt = base.size();
                                String prev = "";
                                for (String x : todOrdered) {
                                    if (x.equals(c)) break;
                                    if (base.contains(x)) prev = x;
                                }
                                if (!prev.isBlank()) {
                                    int idx = base.indexOf(prev);
                                    if (idx >= 0) insertAt = idx + 1;
                                }
                                base.add(insertAt, c);
                            }
                            page1CountryListLine = String.join(", ", base);
                        }
                    }

                    StringBuilder page1BodyBlock = new StringBuilder();
                    int start = -1;
                    for (int i = 0; i < page1Lines.size(); i++) {
                        String s = oneLine(page1Lines.get(i).getText()).replaceAll("\\s+", " ").trim();
                        if (s.isBlank()) continue;
                        String low = s.toLowerCase(Locale.ROOT);
                        boolean anchor = low.contains("transport by") || low.contains("incoterms") || low.contains("ship by")
                                || low.contains("free carrier") || low.contains("fca") || low.contains("fob");
                        if (anchor) {
                            start = i;
                            break;
                        }
                    }
                    if (start >= 0) {
                        int added = 0;
                        for (int j = start; j < Math.min(page1Lines.size(), start + 8); j++) {
                            String sj = oneLine(page1Lines.get(j).getText()).replaceAll("\\s+", " ").trim();
                            if (sj.isBlank()) continue;
                            String lowj = sj.toLowerCase(Locale.ROOT);
                            boolean looksLikeTerms = lowj.contains("transport by")
                                    || lowj.contains("incoterms")
                                    || lowj.contains("ship by")
                                    || lowj.contains("free carrier")
                                    || lowj.contains("service provider information")
                                    || lowj.contains("origin delivery information")
                                    || lowj.contains("account number")
                                    || lowj.contains("account no")
                                    || lowj.contains("fca")
                                    || lowj.contains("fob");
                            if (!looksLikeTerms) {
                                if (added > 0) break;
                                continue;
                            }
                            if (page1BodyBlock.length() > 0) page1BodyBlock.append("\n");
                            page1BodyBlock.append(sj);
                            added++;
                            if (lowj.startsWith("by accepting") || lowj.contains("supplier acknowledges")) break;
                        }
                    }

                    String inferredBodyPage1 = cleanPurchaseOrderTermsOfDeliveryText(page1BodyBlock.toString());
                    if (inferredBodyPage1 != null && !inferredBodyPage1.isBlank()) {
                        String existingTerms = nvl(formFields.get("Terms of Delivery")).trim();
                        String lowExisting = existingTerms.toLowerCase(Locale.ROOT);
                        boolean existingLooksLikeCourier = lowExisting.contains("courier") || lowExisting.contains("dhl") || lowExisting.contains("destination studio address");
                        if (existingTerms.isBlank() || existingLooksLikeCourier) {
                            String prefix = "";
                            if (!page1Code.isBlank()) {
                                prefix = page1Code;
                            } else if (!page1CountryListLine.isBlank()) {
                                prefix = page1CountryListLine;
                            }
                            if (!prefix.isBlank()) {
                                formFields.put("Terms of Delivery", prefix + "\n" + inferredBodyPage1);
                            } else {
                                formFields.put("Terms of Delivery", inferredBodyPage1);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                // Best-effort override only; never fail OCR job for this enrichment.
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

            String termsForInvoiceFallback = termsWithCountries;
            if (termsForInvoiceFallback == null || termsForInvoiceFallback.isBlank()) {
                termsForInvoiceFallback = formFields.get("Terms of Delivery");
            }
            fillMissingPurchaseOrderInvoiceAvgPriceCountries(poInvoiceAvgPrice, allLines, termsForInvoiceFallback);

            if (effectiveDebug) {
                log.info("[PO-INVOICE-PRICE][PIPE] afterFillFromTerms rows={}", poInvoiceAvgPrice == null ? 0 : poInvoiceAvgPrice.size());
                if (poInvoiceAvgPrice != null) {
                    for (int i = 0; i < Math.min(30, poInvoiceAvgPrice.size()); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        log.info("[PO-INVOICE-PRICE][PIPE] afterFillFromTerms[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                nvl(r.get("invoiceAveragePrice")).trim(),
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }

                    for (int i = 0; i < poInvoiceAvgPrice.size(); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        String price = nvl(r.get("invoiceAveragePrice")).trim();
                        if (!price.equals("5.40 USD")) continue;
                        if (poInvoiceAvgPriceTraceBuffer != null) {
                            poInvoiceAvgPriceTraceBuffer.add("[PO-INVOICE-PRICE][TRACE] afterFillFromTerms[" + i
                                    + "] page=" + nvl(r.get("page")).trim()
                                    + " price=" + price
                                    + " country=" + nvl(r.get("country")).trim()
                                    + " src=" + nvl(r.get("_src")).trim());
                        }
                        log.info("[PO-INVOICE-PRICE][TRACE] afterFillFromTerms[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                price,
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }
                }
            }

            deduplicatePurchaseOrderInvoiceAvgPriceRows(poInvoiceAvgPrice);

            if (effectiveDebug) {
                log.info("[PO-INVOICE-PRICE][PIPE] afterDedup rows={}", poInvoiceAvgPrice == null ? 0 : poInvoiceAvgPrice.size());
                if (poInvoiceAvgPrice != null) {
                    for (int i = 0; i < Math.min(30, poInvoiceAvgPrice.size()); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        log.info("[PO-INVOICE-PRICE][PIPE] afterDedup[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                nvl(r.get("invoiceAveragePrice")).trim(),
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }

                    for (int i = 0; i < poInvoiceAvgPrice.size(); i++) {
                        Map<String, String> r = poInvoiceAvgPrice.get(i);
                        if (r == null) continue;
                        String price = nvl(r.get("invoiceAveragePrice")).trim();
                        if (!price.equals("5.40 USD")) continue;
                        if (poInvoiceAvgPriceTraceBuffer != null) {
                            poInvoiceAvgPriceTraceBuffer.add("[PO-INVOICE-PRICE][TRACE] afterDedup[" + i
                                    + "] page=" + nvl(r.get("page")).trim()
                                    + " price=" + price
                                    + " country=" + nvl(r.get("country")).trim()
                                    + " src=" + nvl(r.get("_src")).trim());
                        }
                        log.info("[PO-INVOICE-PRICE][TRACE] afterDedup[{}] page={} price={} country={} src={}",
                                i,
                                nvl(r.get("page")).trim(),
                                price,
                                nvl(r.get("country")).trim(),
                                nvl(r.get("_src")).trim());
                    }
                }
            }
            prefixPurchaseOrderTermsOfDeliveryCountriesFromInvoiceAvgPrice(poTermsOfDeliveryByPage, poInvoiceAvgPrice);

            String globalTermsForNormalization = formFields.get("Terms of Delivery");
            String globalBodyForNormalization = cleanPurchaseOrderTermsOfDeliveryText(
                    stripLeadingCountryLine(nvl(globalTermsForNormalization).trim())
            );
            Pattern todCountryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
            if (todCountryLinePat.matcher(globalBodyForNormalization).matches()) {
                globalBodyForNormalization = "";
            }
            if (globalBodyForNormalization.isBlank()) {
                String inferredBody = inferPurchaseOrderTermsOfDeliveryGlobalBodyFromLines(allLines);
                if (inferredBody != null && !inferredBody.isBlank()) {
                    globalTermsForNormalization = inferredBody;
                }
            }
            normalizePurchaseOrderTermsOfDeliveryTextFromGlobal(poTermsOfDeliveryByPage, globalTermsForNormalization);

            String courierTermsBlock = extractCourierTermsBlockFromPurchaseOrderTermsByPage(poTermsOfDeliveryByPage);
            if (!courierTermsBlock.isBlank()) {
                removeCourierTermsFromPurchaseOrderTermsByPage(poTermsOfDeliveryByPage);
            }

            // For page > 1, display Invoice Avg Price rows using the page's Terms-of-Delivery country
            // codes, mapped to the extracted Invoice Avg Price table.
            fillPurchaseOrderInvoiceAvgPriceCountriesFromTermsByPage(poInvoiceAvgPrice, poTermsOfDeliveryByPage);

            List<Map<String, String>> salesSampleTermsByPage = null;
            List<Map<String, String>> salesSampleTimeOfDeliveryByPage = null;
            List<Map<String, String>> salesSampleDestinationStudioAddressByPage = null;
            List<Map<String, String>> salesSampleArticlesByPage = null;
            if (isPdf(file)) {
                salesSampleTermsByPage = extractSalesSampleTermsByPageFromPdfBytes(fileBytes);
                salesSampleTimeOfDeliveryByPage = extractSalesSampleTimeOfDeliveryByPageFromPdfBytes(fileBytes);
                salesSampleDestinationStudioAddressByPage = extractSalesSampleDestinationStudioAddressByPageFromPdfBytes(fileBytes);
                salesSampleArticlesByPage = extractSalesSampleArticlesByPageFromPdfBytes(fileBytes);

                if (effectiveDebug) {
                    log.info("[SALES-SAMPLE][PDFBOX] terms rows={} dest rows={} tod rows={} articles rows={}",
                            salesSampleTermsByPage == null ? 0 : salesSampleTermsByPage.size(),
                            salesSampleDestinationStudioAddressByPage == null ? 0 : salesSampleDestinationStudioAddressByPage.size(),
                            salesSampleTimeOfDeliveryByPage == null ? 0 : salesSampleTimeOfDeliveryByPage.size(),
                            salesSampleArticlesByPage == null ? 0 : salesSampleArticlesByPage.size());
                    if (salesSampleTermsByPage != null && !salesSampleTermsByPage.isEmpty()) {
                        log.info("[SALES-SAMPLE][PDFBOX] terms sample={}", truncate(oneLine(nvl(salesSampleTermsByPage.get(0).get("salesSampleTerms"))), 220));
                    }
                    if (salesSampleDestinationStudioAddressByPage != null && !salesSampleDestinationStudioAddressByPage.isEmpty()) {
                        log.info("[SALES-SAMPLE][PDFBOX] dest sample={}", truncate(oneLine(nvl(salesSampleDestinationStudioAddressByPage.get(0).get("destinationStudioAddress"))), 220));
                    }
                }
            }
            if (salesSampleTermsByPage == null || salesSampleTermsByPage.isEmpty()) {
                salesSampleTermsByPage = extractSalesSampleTermsByPage(allLines);
            }
            if (salesSampleTimeOfDeliveryByPage == null || salesSampleTimeOfDeliveryByPage.isEmpty()) {
                salesSampleTimeOfDeliveryByPage = extractSalesSampleTimeOfDeliveryByPage(allLines);
            }
            if (salesSampleDestinationStudioAddressByPage == null || salesSampleDestinationStudioAddressByPage.isEmpty()) {
                salesSampleDestinationStudioAddressByPage = extractSalesSampleDestinationStudioAddressByPage(allLines);
            }
            if (salesSampleArticlesByPage == null || salesSampleArticlesByPage.isEmpty()) {
                salesSampleArticlesByPage = extractSalesSampleArticlesByPage(allLines);
            }

            int salesSamplePageCount = 0;
            if (cleanedPageImages != null && !cleanedPageImages.isEmpty()) {
                salesSamplePageCount = cleanedPageImages.size();
            } else if (allLines != null && !allLines.isEmpty()) {
                for (OcrNewLine l : allLines) {
                    if (l == null) continue;
                    if (l.getPage() > salesSamplePageCount) salesSamplePageCount = l.getPage();
                }
            }

            List<Map<String, String>> salesSampleTermsOfDeliveryByPage = null;
            if (isPdf(file)) {
                salesSampleTermsOfDeliveryByPage = extractSalesSampleTermsOfDeliveryByPageFromPdfBytes(fileBytes);

                if (effectiveDebug) {
                    log.info("[SALES-SAMPLE][PDFBOX] termsOfDelivery rows={}", salesSampleTermsOfDeliveryByPage == null ? 0 : salesSampleTermsOfDeliveryByPage.size());
                    if (salesSampleTermsOfDeliveryByPage != null && !salesSampleTermsOfDeliveryByPage.isEmpty()) {
                        log.info("[SALES-SAMPLE][PDFBOX] termsOfDelivery sample={}", truncate(oneLine(nvl(salesSampleTermsOfDeliveryByPage.get(0).get("termsOfDelivery"))), 220));
                    }
                }
            }
            if (salesSampleTermsOfDeliveryByPage == null || salesSampleTermsOfDeliveryByPage.isEmpty()) {
                salesSampleTermsOfDeliveryByPage = new ArrayList<>();
                String staticBlock = courierTermsBlock;
                if (staticBlock.isBlank()) {
                    staticBlock = inferSalesSampleCourierTermsBlockFromAllLines(allLines);
                }
                if (!staticBlock.isBlank() && salesSamplePageCount > 0) {
                    salesSampleTermsOfDeliveryByPage = buildSalesSampleTermsOfDeliveryStaticAllPages(salesSamplePageCount, staticBlock);
                }
            }

            if (effectiveDebug) {
                log.info("[SALES-SAMPLE-TERMS-DELIVERY][PIPE] pageCount={} courierTermsBlockBlank={} rows={} (afterPdfbox+fallback)",
                        salesSamplePageCount,
                        courierTermsBlock.isBlank(),
                        salesSampleTermsOfDeliveryByPage == null ? 0 : salesSampleTermsOfDeliveryByPage.size());
                if (salesSampleTermsOfDeliveryByPage != null) {
                    for (int i = 0; i < Math.min(5, salesSampleTermsOfDeliveryByPage.size()); i++) {
                        Map<String, String> r = salesSampleTermsOfDeliveryByPage.get(i);
                        if (r == null) continue;
                        log.info("[SALES-SAMPLE-TERMS-DELIVERY][PIPE] row[{}]: page={} src={} textPreview={}",
                                i,
                                nvl(r.get("page")).trim(),
                                nvl(r.get("_src")).trim(),
                                truncate(nvl(r.get("termsOfDelivery")), 220));
                    }
                }
            }

            // Make Sales Sample Terms + Time Of Delivery static across all tabs.
            if (salesSamplePageCount > 0) {
                String staticSalesSampleTerms = chooseStaticSalesSampleTermsText(salesSampleTermsByPage);
                if (!staticSalesSampleTerms.isBlank()) {
                    upsertSalesSampleTermsStaticAllPages(salesSampleTermsByPage, salesSamplePageCount, staticSalesSampleTerms);
                }

                String staticSalesSampleTermsOfDelivery = chooseStaticSalesSampleTermsOfDeliveryText(salesSampleTermsOfDeliveryByPage);
                if (!staticSalesSampleTermsOfDelivery.isBlank()) {
                    upsertSalesSampleTermsOfDeliveryStaticAllPages(salesSampleTermsOfDeliveryByPage, salesSamplePageCount, staticSalesSampleTermsOfDelivery);
                }

                String staticSalesSampleTod = chooseStaticSalesSampleTimeOfDeliveryText(salesSampleTimeOfDeliveryByPage);
                if (!staticSalesSampleTod.isBlank()) {
                    upsertSalesSampleTimeOfDeliveryStaticAllPages(salesSampleTimeOfDeliveryByPage, salesSamplePageCount, staticSalesSampleTod);
                }
            }
            if (effectiveDebug) {
                log.info("[SALES-SAMPLE-TERMS] extracted rows: {}", salesSampleTermsByPage == null ? 0 : salesSampleTermsByPage.size());
                for (int i = 0; i < Math.min(10, salesSampleTermsByPage == null ? 0 : salesSampleTermsByPage.size()); i++) {
                    Map<String, String> r = salesSampleTermsByPage.get(i);
                    if (r == null) continue;
                    log.info("[SALES-SAMPLE-TERMS] row[{}]: page={} textPreview={}", i, r.get("page"), truncate(r.get("salesSampleTerms"), 200));
                }

                log.info("[SALES-SAMPLE-TERMS-DELIVERY] extracted rows: {}", salesSampleTermsOfDeliveryByPage == null ? 0 : salesSampleTermsOfDeliveryByPage.size());
                for (int i = 0; i < Math.min(10, salesSampleTermsOfDeliveryByPage == null ? 0 : salesSampleTermsOfDeliveryByPage.size()); i++) {
                    Map<String, String> r = salesSampleTermsOfDeliveryByPage.get(i);
                    if (r == null) continue;
                    log.info("[SALES-SAMPLE-TERMS-DELIVERY] row[{}]: page={} src={} textPreview={}",
                            i,
                            nvl(r.get("page")).trim(),
                            nvl(r.get("_src")).trim(),
                            truncate(nvl(r.get("termsOfDelivery")), 220));
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

                log.info("[SALES-SAMPLE-DEST-STUDIO-ADDR] extracted rows: {}", salesSampleDestinationStudioAddressByPage == null ? 0 : salesSampleDestinationStudioAddressByPage.size());
                if (salesSampleDestinationStudioAddressByPage != null && !salesSampleDestinationStudioAddressByPage.isEmpty()) {
                    Map<String, String> r = salesSampleDestinationStudioAddressByPage.get(0);
                    log.info("[SALES-SAMPLE-DEST-STUDIO-ADDR] first: page={} textPreview={}", r.get("page"), truncate(r.get("destinationStudioAddress"), 220));
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
            int ocrResultLineLimit = 120;
            for (int li = 0; li < Math.min(allLines.size(), ocrResultLineLimit); li++) {
                OcrNewLine l = allLines.get(li);
                log.info("[OCR-RESULT][LINE] file={} page={} line[{}]: {}", fname, l.getPage(), li, truncate(oneLine(l.getText()), 400));
            }
            if (allLines.size() > ocrResultLineLimit) {
                log.info("[OCR-RESULT][LINE] file={} {} more lines not logged", fname, allLines.size() - ocrResultLineLimit);
            }
            // Dump form fields
            int ocrResultFieldLimit = 120;
            List<Map.Entry<String, String>> fieldEntries = new ArrayList<>(formFields.entrySet());
            fieldEntries.sort(Map.Entry.comparingByKey());
            for (int fi = 0; fi < Math.min(fieldEntries.size(), ocrResultFieldLimit); fi++) {
                Map.Entry<String, String> en = fieldEntries.get(fi);
                log.info("[OCR-RESULT][FIELD] file={} {} = {}", fname, en.getKey(), truncate(oneLine(en.getValue()), 300));
            }
            if (fieldEntries.size() > ocrResultFieldLimit) {
                log.info("[OCR-RESULT][FIELD] file={} {} more fields not logged", fname, fieldEntries.size() - ocrResultFieldLimit);
            }
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

            if (poInvoiceAvgPriceTraceBuffer != null && !poInvoiceAvgPriceTraceBuffer.isEmpty()) {
                for (String s : poInvoiceAvgPriceTraceBuffer) {
                    log.info("[PO-INVOICE-PRICE][TRACE-END] {}", s);
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
                    .totalCountryBreakdown(totalCountryBreakdown)
                    .colourSizeBreakdown(colourSizeBreakdown)
                    .purchaseOrderTimeOfDelivery(poTimeOfDelivery)
                    .purchaseOrderQuantityPerArticle(poQuantityPerArticle)
                    .purchaseOrderInvoiceAvgPrice(poInvoiceAvgPrice)
                    .purchaseOrderTermsOfDelivery(poTermsOfDeliveryByPage)
                    .salesSampleTermsByPage(salesSampleTermsByPage)
                    .salesSampleTermsOfDeliveryByPage(salesSampleTermsOfDeliveryByPage)
                    .salesSampleTimeOfDeliveryByPage(salesSampleTimeOfDeliveryByPage)
                    .salesSampleDestinationStudioAddressByPage(salesSampleDestinationStudioAddressByPage)
                    .salesSampleArticlesByPage(salesSampleArticlesByPage)
                    .averageConfidence(avgConfidence)
                    .pageCount(pageImages.size())
                    .build();

        } catch (IOException e) {
            log.error("OCR-NEW file read failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }

    }

    private static void normalizeTotalCountryBreakdownArticleKeys(
            List<Map<String, String>> totalCountryBreakdown,
            List<Map<String, String>> colourSizeBreakdown,
            Map<String, String> articleLabelByNo
    ) {
        if (totalCountryBreakdown == null || totalCountryBreakdown.isEmpty()) return;

        Map<String, String> labelByArticleNo = new LinkedHashMap<>();
        if (articleLabelByNo != null && !articleLabelByNo.isEmpty()) {
            labelByArticleNo.putAll(articleLabelByNo);
        }
        if (labelByArticleNo.isEmpty() && colourSizeBreakdown != null && !colourSizeBreakdown.isEmpty()) {
            Pattern labelPat = Pattern.compile("\\b(\\d{3})\\b.*?\\b(\\d{2}-\\d{3})\\b");
            for (Map<String, String> r : colourSizeBreakdown) {
                if (r == null) continue;
                String a = nvl(r.get("article")).trim();
                if (a.isBlank()) continue;
                Matcher m = labelPat.matcher(a);
                if (!m.find()) continue;
                String artNo = m.group(1);
                String label = (m.group(1) + " " + m.group(2)).trim();
                if (!labelByArticleNo.containsKey(artNo)) labelByArticleNo.put(artNo, label);
            }
        }
        if (labelByArticleNo.isEmpty()) return;

        Pattern artKeyPat = Pattern.compile("(?i)\\barticle\\b[^0-9]*(\\d{3})\\b");
        for (int i = 0; i < totalCountryBreakdown.size(); i++) {
            Map<String, String> row = totalCountryBreakdown.get(i);
            if (row == null || row.isEmpty()) continue;

            Map<String, String> next = new LinkedHashMap<>();
            for (Map.Entry<String, String> en : row.entrySet()) {
                String k = en.getKey();
                String v = en.getValue();
                if (k == null) continue;

                Matcher km = artKeyPat.matcher(k);
                if (km.find()) {
                    String artNo = km.group(1);
                    String label = labelByArticleNo.get(artNo);
                    if (label != null && !label.isBlank()) {
                        next.put("Article:" + label, v);
                        continue;
                    }
                }
                next.put(k, v);
            }
            totalCountryBreakdown.set(i, next);
        }
    }

    private static void fillColourSizeBreakdownArticleLabelsFromHeader(
            List<Map<String, String>> colourSizeBreakdown,
            Map<String, String> formFields
    ) {
        if (colourSizeBreakdown == null || colourSizeBreakdown.isEmpty()) return;
        if (formFields == null || formFields.isEmpty()) return;

        String articleNo = "";
        String hmColourCode = "";
        for (Map.Entry<String, String> en : formFields.entrySet()) {
            String k = nvl(en.getKey()).trim().toLowerCase(Locale.ROOT);
            String v = nvl(en.getValue()).trim();
            if (v.isBlank()) continue;
            if (articleNo.isBlank() && (k.equals("article no") || k.equals("article") || k.equals("article / product no") || k.equals("article / product") || k.equals("product no"))) {
                articleNo = v;
            }
            if (hmColourCode.isBlank() && (k.equals("h&m colour code") || k.equals("h&m colour") || k.equals("h&m color code") || k.equals("h&m color"))) {
                hmColourCode = v;
            }
        }

        Matcher am = Pattern.compile("\\b(\\d{3})\\b").matcher(articleNo);
        Matcher cm = Pattern.compile("\\b(\\d{2}-\\d{3})\\b").matcher(hmColourCode);
        String a3 = am.find() ? am.group(1) : "";
        String c23 = cm.find() ? cm.group(1) : "";
        if (a3.isBlank() || c23.isBlank()) return;

        String label = (a3 + " " + c23).trim();
        for (Map<String, String> r : colourSizeBreakdown) {
            if (r == null) continue;
            String a = nvl(r.get("article")).trim();
            if (a.isBlank()) {
                r.put("article", label);
                continue;
            }
            // Only replace weak forms like "001" or "Article:001"; keep already-rich labels.
            if (a.matches("^\\d{3}$") || a.matches("(?i)^article\\s*[:#-]?\\s*\\d{3}$")) {
                r.put("article", label);
            }
        }
    }

    private static Map<String, String> buildArticleLabelByNoFromDetail(List<Map<String, String>> detailRows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (detailRows == null || detailRows.isEmpty()) return out;

        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");
        Pattern cPat = Pattern.compile("\\b(\\d{2}-\\d{3})\\b");
        for (Map<String, String> r : detailRows) {
            if (r == null) continue;
            String a0 = nvl(r.get("articleNo")).trim();
            String c0 = nvl(r.get("hmColourCode")).trim();
            if (a0.isBlank() || c0.isBlank()) continue;
            Matcher am = aPat.matcher(a0);
            Matcher cm = cPat.matcher(c0);
            if (!am.find() || !cm.find()) continue;
            String a = am.group(1);
            String c = cm.group(1);
            String label = (a + " " + c).trim();
            if (!out.containsKey(a)) out.put(a, label);
        }
        return out;
    }

    private static void applyArticleLabelsToDetailRows(List<Map<String, String>> detailRows, Map<String, String> labelByArticleNo) {
        if (detailRows == null || detailRows.isEmpty()) return;
        if (labelByArticleNo == null || labelByArticleNo.isEmpty()) return;

        Pattern aPat = Pattern.compile("\\b(\\d{3})\\b");
        for (Map<String, String> r : detailRows) {
            if (r == null) continue;
            String a0 = nvl(r.get("articleNo")).trim();
            if (a0.isBlank()) continue;
            Matcher m = aPat.matcher(a0);
            if (!m.find()) continue;
            String a = m.group(1);
            String label = labelByArticleNo.get(a);
            if (label == null || label.isBlank()) continue;

            // Overwrite articleNo so the UI (which binds to articleNo) displays the full label.
            r.put("articleNo", label);
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

    private static boolean hasStandaloneToken(List<OcrNewLine> allLines, String token) {
        if (allLines == null || token == null) return false;
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String s = oneLine(l.getText()).trim();
            if (s.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static List<String> extractPurchaseOrderTodCodesOrderedFromParsedTable(List<Map<String, String>> poTimeOfDelivery, boolean normalizeOlToOi) {
        if (poTimeOfDelivery == null || poTimeOfDelivery.isEmpty()) return Collections.emptyList();
        Pattern codePat = Pattern.compile("\\b([A-Z]{2})\\s*\\(");
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (Map<String, String> row : poTimeOfDelivery) {
            if (row == null) continue;
            String pm = safe(row.get("planningMarkets")).replaceAll("\\s+", " ").trim();
            if (pm.isBlank()) continue;
            Matcher m = codePat.matcher(pm.toUpperCase(Locale.ROOT));
            while (m.find()) {
                String c = m.group(1);
                if (c == null) continue;
                String cc = c.trim().toUpperCase(Locale.ROOT);
                if (normalizeOlToOi && cc.equals("OL")) cc = "OI";
                if (!cc.isBlank()) ordered.add(cc);
            }
            // Fallback if planningMarkets is already a 2-letter token
            if (ordered.isEmpty()) {
                String pmUp = pm.toUpperCase(Locale.ROOT);
                if (pmUp.matches("^[A-Z]{2}$")) {
                    String cc = pmUp;
                    if (normalizeOlToOi && cc.equals("OL")) cc = "OI";
                    ordered.add(cc);
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private static String addPurchaseOrderTermsDeliveryCountryCodes(String termsOfDelivery, List<OcrNewLine> allLines, List<Map<String, String>> poTimeOfDelivery) {
        String existing0 = safe(termsOfDelivery).trim();
        if (!existing0.isBlank()) {
            String[] p0 = existing0.split("\\R", 2);
            String first0 = p0.length > 0 ? p0[0].trim() : "";
            String body0 = p0.length > 1 ? p0[1].trim() : "";

            // Some PDFs (especially store purchase orders) use a planning-market style header line like:
            // "NL/OE, OU, OG, ..." which is NOT a simple country-code list. In these cases we must not
            // prepend/replace with inferred 2-letter country codes from Time-of-Delivery; doing so pollutes
            // the header and makes the UI show an invalid/too-long codes list.
            Pattern slashTokenPat0 = Pattern.compile("\\b[A-Z]{2}\\s*/\\s*[A-Z0-9]{2}\\b");
            if (slashTokenPat0.matcher(first0.toUpperCase(Locale.ROOT)).find()) {
                return existing0;
            }

            Pattern leadingCountryLinePat0 = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
            if (leadingCountryLinePat0.matcher(first0.toUpperCase(Locale.ROOT)).matches() && !body0.isBlank()) {
                String low0 = body0.toLowerCase(Locale.ROOT);
                boolean bodyLooksValid0 = low0.contains("transport by") || low0.contains("incoterms") || low0.contains("ship by")
                        || low0.contains("free carrier") || low0.contains("fca") || low0.contains("fob");
                if (bodyLooksValid0) {
                    boolean normalizeOlToOi = hasStandaloneToken(allLines, "OI");
                    List<String> orderedTod = extractPurchaseOrderTodCodesOrderedFromParsedTable(poTimeOfDelivery, normalizeOlToOi);
                    if (orderedTod != null && !orderedTod.isEmpty()) {
                        LinkedHashSet<String> existingSet = new LinkedHashSet<>();
                        for (String t : first0.split("\\s*,\\s*")) {
                            String tt = safe(t).trim().toUpperCase(Locale.ROOT);
                            if (normalizeOlToOi && tt.equals("OL")) tt = "OI";
                            if (!tt.isBlank()) existingSet.add(tt);
                        }
                        int overlap = 0;
                        for (String c : orderedTod) if (existingSet.contains(c)) overlap++;
                        boolean shouldReplace = orderedTod.size() > existingSet.size() && overlap >= Math.max(1, orderedTod.size() / 2);
                        if (shouldReplace) {
                            String newFirst = String.join(", ", orderedTod);
                            return newFirst + "\n" + body0;
                        }

                        // Otherwise, merge missing TOD codes into the existing list while preserving
                        // the original ordering as much as possible (PDF-like) and inserting new codes
                        // according to the TOD sequence (no hardcoded canonical list).
                        List<String> base = new ArrayList<>();
                        for (String t : first0.split("\\s*,\\s*")) {
                            String tt = safe(t).trim().toUpperCase(Locale.ROOT);
                            if (normalizeOlToOi && tt.equals("OL")) tt = "OI";
                            if (!tt.isBlank() && !base.contains(tt)) base.add(tt);
                        }
                        boolean changed = false;
                        for (String c : orderedTod) {
                            if (c == null || c.isBlank()) continue;
                            String cc = c.trim().toUpperCase(Locale.ROOT);
                            if (normalizeOlToOi && cc.equals("OL")) cc = "OI";
                            if (base.contains(cc)) continue;

                            int insertAt = base.size();
                            String prev = "";
                            for (String x : orderedTod) {
                                if (x == null) continue;
                                String xx = x.trim().toUpperCase(Locale.ROOT);
                                if (normalizeOlToOi && xx.equals("OL")) xx = "OI";
                                if (xx.equals(cc)) break;
                                if (base.contains(xx)) prev = xx;
                            }
                            if (!prev.isBlank()) {
                                int idx = base.indexOf(prev);
                                if (idx >= 0) insertAt = idx + 1;
                            }
                            base.add(insertAt, cc);
                            changed = true;
                        }
                        if (changed) {
                            String newFirst = String.join(", ", base);
                            return newFirst + "\n" + body0;
                        }
                    }
                    return existing0;
                }
            }
        }

        // Prefer parsed Time-of-Delivery table (most reliable, scoped to the planning market list)
        // to avoid accidentally collecting random 2-letter tokens from addresses/other text.
        String codesSource = "parsedTable";
        boolean normalizeOlToOi = hasStandaloneToken(allLines, "OI");
        List<String> orderedTod = extractPurchaseOrderTodCodesOrderedFromParsedTable(poTimeOfDelivery, normalizeOlToOi);
        LinkedHashSet<String> codes = new LinkedHashSet<>(orderedTod);
        if (codes.isEmpty()) {
            codesSource = "expanded";
            codes = extractPurchaseOrderPlanningMarketCountryCodesExpanded(allLines, poTimeOfDelivery);
        }
        if (codes.isEmpty()) {
            codesSource = "allLines";
            codes = extractPurchaseOrderPlanningMarketCountryCodes(allLines);
        }
        if (codes.isEmpty()) return termsOfDelivery;
        if (log.isDebugEnabled()) {
            log.debug("[PO-TOD-CODES] source={} size={} codes={}", codesSource, codes.size(), codes);
        }

        LinkedHashSet<String> ordered = codes;
        if (allLines != null && !allLines.isEmpty()) {
            Pattern anyCountryListPat = Pattern.compile("((?:[A-Z]{2}\\s*,\\s*){3,}[A-Z]{2})\\b");
            Pattern twoLetterPat = Pattern.compile("\\b([A-Z]{2})\\b");

            List<String> best = null;
            for (OcrNewLine l : allLines) {
                if (l == null || l.getText() == null) continue;
                String t = oneLine(l.getText()).trim().toUpperCase(Locale.ROOT);
                if (t.isBlank()) continue;

                Matcher em = anyCountryListPat.matcher(t);
                while (em.find()) {
                    String chunk = em.group(1);
                    if (chunk == null || chunk.isBlank()) continue;

                    List<String> found = new ArrayList<>();
                    Matcher cm = twoLetterPat.matcher(chunk);
                    while (cm.find()) {
                        String c = cm.group(1);
                        if (c == null || c.isBlank()) continue;
                        c = c.trim();
                        // OCR often confuses I with l in codes like OI => OL.
                        if (c.equals("OL") && codes.contains("OI") && !codes.contains("OL")) {
                            c = "OI";
                        }
                        found.add(c);
                    }
                    if (found.size() >= 4 && (best == null || found.size() > best.size())) {
                        best = found;
                    }
                }
            }

            if (best != null && !best.isEmpty()) {
                int overlap = 0;
                for (String c : best) {
                    if (c == null || c.isBlank()) continue;
                    String cc = c.trim().toUpperCase(Locale.ROOT);
                    if (codes.contains(cc)) overlap++;
                }
                double overlapRatio = best.isEmpty() ? 0d : (overlap * 1.0d / best.size());

                // Only trust the OCR-detected country list line when it is clearly describing
                // the same planning market codes set. This prevents unrelated 2-letter tokens
                // (e.g. from addresses/other sections) from expanding a valid single-code TOD.
                boolean trustBestList = overlap >= 4 && overlapRatio >= 0.60d;
                if (log.isDebugEnabled()) {
                    log.debug("[PO-TOD-CODES] bestList size={} overlap={} overlapRatio={} trustBestList={}",
                            best.size(), overlap, String.format(Locale.ROOT, "%.2f", overlapRatio), trustBestList);
                }
                if (trustBestList) {
                    LinkedHashSet<String> ord = new LinkedHashSet<>();
                    for (String c : best) {
                        if (c != null && !c.isBlank()) ord.add(c.trim().toUpperCase(Locale.ROOT));
                    }
                    for (String c : codes) {
                        if (!ord.contains(c)) ord.add(c);
                    }
                    ordered = ord;
                }
            }
        }

        String codesLine = String.join(", ", ordered);

        String existing = safe(termsOfDelivery).trim();
        if (existing.toUpperCase(Locale.ROOT).startsWith(codesLine)) return existing;

        String[] parts = existing.split("\\R", 2);
        String firstLine = parts.length > 0 ? parts[0].trim() : "";
        String body = parts.length > 1 ? parts[1].trim() : "";
        Pattern leadingCountryLinePat = Pattern.compile("^(?:[A-Z]{2}\\s*,\\s*)*[A-Z]{2}$");
        if (leadingCountryLinePat.matcher(firstLine).matches()) {
            // If the existing country list already contains the same set of codes, preserve the
            // original ordering because it is the closest to the PDF's ordering.
            LinkedHashSet<String> existingCodes = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\b([A-Z]{2})\\b").matcher(firstLine.toUpperCase(Locale.ROOT));
            while (m.find()) {
                existingCodes.add(m.group(1));
            }
            if (!existingCodes.isEmpty()) {
                LinkedHashSet<String> computedCodes = new LinkedHashSet<>();
                for (String c : ordered) {
                    if (c != null && !c.isBlank()) computedCodes.add(c.trim().toUpperCase(Locale.ROOT));
                }
                if (!computedCodes.isEmpty() && existingCodes.size() == computedCodes.size() && computedCodes.containsAll(existingCodes)) {
                    return existing;
                }
            }
            if (body.isBlank()) return codesLine;
            return codesLine + "\n" + body;
        }

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
                        ) {
                    break;
                }

                if (low.equals("hong kong")) {
                    continue;
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

            pageLines = new ArrayList<>(pageLines);
            pageLines.sort(Comparator
                    .comparingInt(OcrNewLine::getTop)
                    .thenComparingInt(OcrNewLine::getLeft));

            // Guard against false positives on non-table pages: we only run the extractor
            // when the page contains a plausible header in the expected region.
            int headerIdx = -1;
            int headerTop = -1;
            for (int i = 0; i < pageLines.size(); i++) {
                OcrNewLine l = pageLines.get(i);
                if (l == null || l.getText() == null) continue;
                String s = oneLine(l.getText()).trim();
                if (s.isBlank()) continue;
                String low = s.toLowerCase(Locale.ROOT);
                boolean looksLikeHeader = (low.contains("invoice average") || low.contains("invoice avg"))
                        && low.contains("country");
                // Reject key-value artefacts like "Invoice Average Price = Country".
                if (looksLikeHeader && !s.contains("=") && l.getLeft() <= 650 && l.getTop() >= 1400) {
                    headerIdx = i;
                    headerTop = l.getTop();
                    break;
                }
            }
            if (headerIdx < 0) continue;

            List<OcrNewLine> scoped = new ArrayList<>();
            int topMin = Math.max(0, headerTop - 20);
            int topMax = headerTop + 320;
            for (int i = headerIdx; i < pageLines.size(); i++) {
                OcrNewLine l = pageLines.get(i);
                if (l == null || l.getText() == null) continue;
                if (l.getTop() < topMin) continue;
                if (l.getTop() > topMax) break;
                // The table is on the left side; ignore far-right columns to avoid picking up
                // unrelated prices/codes elsewhere on the page.
                if (l.getLeft() > 900) continue;
                scoped.add(l);
            }

            List<Map<String, String>> rows = extractPurchaseOrderInvoiceAvgPrice(scoped);
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

        // Always merge in table-parsed codes as well, because region OCR fills planningMarkets
        // into poTimeOfDelivery without adding those tokens into allLines.
        out.addAll(extractPurchaseOrderPlanningMarketCountryCodesFromParsedTable(poTimeOfDelivery));

        // Stabilize ordering: follow the canonical PMSEU expansion order (plus TW) when possible.
        // This matches how the PDF typically lists markets and avoids order drift from OCR discovery order.
        if (!out.isEmpty()) {
            String[] canonical = new String[] {
                    "SE", "DE", "BE", "US", "PL", "JP", "KR", "CH", "CA", "TR", "MX", "MY", "RS", "PH", "IN", "CO", "VN", "EC", "GB", "ME", "IX", "TH", "ID", "TW", "PA"
            };
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String c : canonical) {
                if (out.contains(c)) ordered.add(c);
            }
            for (String c : out) {
                if (!ordered.contains(c)) ordered.add(c);
            }
            return ordered;
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

        Pattern planningTokenPat = Pattern.compile("\\b([A-Z]{2})\\s*\\(\\s*(?:PM|OL|PIM)[- ]?[A-Z0-9]{2,}\\s*\\)");
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
     * H&M PO/SO header is laid out in two columns. PDFBox text layer reports
     * each visual row as ONE line, so a value like
     *   "Order No: 409456-1522 Product No: 1126584"
     * is parsed as: key="Order No", value="409456-1522 Product No: 1126584"
     * and the right-column key (Product No) is never extracted.
     *
     * This helper scans every value for known right-column labels followed by
     * a colon, splits the value at the boundary, trims the left value to the
     * portion BEFORE the embedded label, and registers the right key/value
     * pair (without overwriting an existing non-blank value).
     */
    static void splitMultiColumnHeaderValues(Map<String, String> formFields) {
        if (formFields == null || formFields.isEmpty()) return;

        // Right-column labels seen in H&M PO/SO header pages.
        // Order matters: longer/more-specific labels MUST come first because
        // Java regex alternation tries left-to-right and takes the first match.
        String[] labels = new String[] {
                "Customs Customer Group",
                "Customer Group",
                "Type of Construction",
                "Product Description",
                "Product Name",
                "Product No",
                "PT Prod No",
                "Packing Mode",
                "No of Pieces",
                "Sales Mode",
                "Season"
        };

        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) alt.append('|');
            alt.append(Pattern.quote(labels[i]));
        }
        // (?i) case-insensitive; \\b ensures label starts at a word boundary
        Pattern boundary = Pattern.compile("(?i)\\b(" + alt + ")\\s*:\\s*");

        // Snapshot to safely mutate formFields while iterating.
        Map<String, String> snapshot = new LinkedHashMap<>(formFields);
        for (Map.Entry<String, String> en : snapshot.entrySet()) {
            String k = en.getKey();
            String v = nvl(en.getValue()).trim();
            if (k == null || v.isEmpty()) continue;

            // If the value itself starts exactly with one of these labels we
            // shouldn't try to "split" it — that's actually the value of that
            // very key. The KV parser already gave us key+value correctly.
            // We only care about EMBEDDED labels appearing AFTER some text.
            Matcher firstM = boundary.matcher(v);
            if (!firstM.find()) continue;
            int firstStart = firstM.start();
            // Need some real text BEFORE the boundary to consider it a 2-column row.
            String leftPart = v.substring(0, firstStart).trim();
            // Strip trailing punctuation noise.
            leftPart = leftPart.replaceAll("[\\s,;|]+$", "").trim();
            if (leftPart.isEmpty()) continue;

            // Collect every (label, valueStart) boundary inside the original value.
            List<int[]> bounds = new ArrayList<>();
            List<String> rightKeys = new ArrayList<>();
            Matcher m2 = boundary.matcher(v);
            while (m2.find()) {
                bounds.add(new int[] { m2.start(), m2.end() });
                rightKeys.add(canonicalHeaderLabel(m2.group(1)));
            }
            if (bounds.isEmpty()) continue;

            // 1) Replace the LEFT key's value with just the cleaned left part.
            if (!leftPart.equals(v)) {
                formFields.put(k, leftPart);
            }

            // 2) For each embedded right-column label, extract its value range
            //    until the NEXT boundary (or end of string).
            for (int i = 0; i < bounds.size(); i++) {
                int valStart = bounds.get(i)[1];
                int valEnd = (i + 1 < bounds.size()) ? bounds.get(i + 1)[0] : v.length();
                String rightVal = v.substring(valStart, valEnd).trim();
                rightVal = rightVal.replaceAll("[\\s,;|]+$", "").trim();
                if (rightVal.isEmpty()) continue;

                String rightKey = rightKeys.get(i);
                String existing = nvl(formFields.get(rightKey)).trim();
                if (existing.isEmpty()) {
                    formFields.put(rightKey, rightVal);
                } else {
                    // If existing value is itself a polluted multi-column line,
                    // and the new clean value is a clean prefix, prefer the new one.
                    Matcher exM = boundary.matcher(existing);
                    if (exM.find()) {
                        formFields.put(rightKey, rightVal);
                    }
                }
            }
        }
    }

    /**
     * Normalize a label captured from regex back to the canonical form used
     * in the formFields map (preserves the casing of the labels[] array).
     */
    private static String canonicalHeaderLabel(String captured) {
        if (captured == null) return "";
        String low = captured.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        switch (low) {
            case "customs customer group": return "Customs Customer Group";
            case "customer group":         return "Customer Group";
            case "type of construction":   return "Type of Construction";
            case "product description":    return "Product Description";
            case "product name":           return "Product Name";
            case "product no":             return "Product No";
            case "pt prod no":             return "PT Prod No";
            case "packing mode":           return "Packing Mode";
            case "no of pieces":           return "No of Pieces";
            case "sales mode":             return "Sales Mode";
            case "season":                 return "Season";
            default:                       return captured.trim();
        }
    }

    static void enrichPurchaseOrderHeaderFields(Map<String, String> formFields, List<OcrNewLine> allLines) {
        if (formFields == null || allLines == null || allLines.isEmpty()) return;

        java.util.function.Function<String, String> get = (String k) -> {
            if (k == null) return "";
            String v = formFields.get(k);
            return v == null ? "" : v.toString().trim();
        };
        java.util.function.BiConsumer<String, String> putIfBlank = (String k, String v) -> {
            if (k == null || v == null) return;
            String cur = get.apply(k);
            String tv = v.trim();
            if (cur.isBlank() && !tv.isBlank()) {
                formFields.put(k, tv);
            }
        };

        // 0) Aliases: H&M PDFs label some fields without the trailing "No",
        //    while the frontend looks them up under the longer name.
        //    Mirror so both keys hold the same clean value.
        {
            String dev = get.apply("Development No");
            if (dev.isBlank()) dev = get.apply("Development");
            if (!dev.isBlank()) {
                putIfBlank.accept("Development No", dev);
                putIfBlank.accept("Development", dev);
            }

            String dateOfOrder = get.apply("Date of Order");
            if (!dateOfOrder.isBlank()) {
                // Some frontends look up "Order Date" instead.
                putIfBlank.accept("Order Date", dateOfOrder);
            } else {
                String od = get.apply("Order Date");
                if (!od.isBlank()) putIfBlank.accept("Date of Order", od);
            }

            String top = get.apply("Terms of Payment");
            if (top.isBlank()) top = get.apply("Term of Payment");
            if (!top.isBlank()) {
                putIfBlank.accept("Terms of Payment", top);
                putIfBlank.accept("Term of Payment", top);
            }
        }

        // 1) Customer Group
        {
            String cg = get.apply("Customs Customer Group");
            if (cg.isBlank()) cg = get.apply("Customer Group");
            if (cg.isBlank()) {
                // Try to salvage from any key that looks like customer group
                for (Map.Entry<String, String> en : formFields.entrySet()) {
                    String k = nvl(en.getKey());
                    String v = nvl(en.getValue()).trim();
                    if (v.isBlank()) continue;
                    String lowK = k.toLowerCase(Locale.ROOT);
                    if (lowK.contains("customer") && lowK.contains("group")) {
                        cg = v;
                        break;
                    }
                }
            }
            if (cg.isBlank()) {
                // OCR fallback: find label and read the value to the right in the same row
                String recovered = extractValueRightOfLabel(allLines,
                        (t) -> t.contains("customer") && t.contains("group"),
                        (t) -> !t.contains("customer") && !t.contains("group"));
                cg = recovered;
            }

            if (cg.isBlank()) {
                // Common OCR miss: label "Customs Customer Group" isn't detected at all,
                // but the value (e.g. "Women") is placed at the far-right of the "Supplier Name" row.
                String recovered = extractValueRightOfLabel(allLines,
                        (t) -> t.contains("supplier") && t.contains("name"),
                        (t) -> true);
                cg = recovered;
            }
            putIfBlank.accept("Customs Customer Group", cg);
            putIfBlank.accept("Customer Group", cg);
        }

        // 2) Country of Origin
        {
            String coo = get.apply("Country of Origin");
            if (coo.isBlank()) {
                // Some OCR output splits "Country of Ori" + "gin: Mainland China" and the parser stores key "gin".
                String gin = get.apply("gin");
                if (!gin.isBlank()) {
                    coo = gin;
                }
            }
            if (coo.isBlank()) {
                // Also try any key that looks like country of origin
                for (Map.Entry<String, String> en : formFields.entrySet()) {
                    String k = nvl(en.getKey());
                    String v = nvl(en.getValue()).trim();
                    if (v.isBlank()) continue;
                    String lowK = k.toLowerCase(Locale.ROOT);
                    if (lowK.contains("country") && lowK.contains("origin")) {
                        coo = v;
                        break;
                    }
                }
            }
            if (coo.isBlank()) {
                String recovered = extractValueRightOfLabel(allLines,
                        (t) -> t.contains("country") && t.contains("ori"),
                        (t) -> true);
                // strip possible "gin:" prefix if the OCR merged label tail
                coo = nvl(recovered).replaceFirst("(?i)^gin\\s*:\\s*", "").trim();
            }
            putIfBlank.accept("Country of Origin", coo);
        }

        // 3) No of Pieces
        {
            String nop = get.apply("No of Pieces");
            if (nop.isBlank()) nop = get.apply("No Pieces");
            if (nop.isBlank()) {
                String recovered = extractValueRightOfLabel(allLines,
                        (t) -> t.contains("no") && t.contains("pieces"),
                        (t) -> true);
                nop = recovered;
            }
            // keep only numeric-ish content if the OCR includes noise
            if (nop != null) {
                String cleaned = nop.replaceAll("[^0-9]", "").trim();
                if (!cleaned.isBlank()) nop = cleaned;
            }
            putIfBlank.accept("No of Pieces", nop);
        }
    }

    private static String extractValueRightOfLabel(
            List<OcrNewLine> allLines,
            java.util.function.Predicate<String> isLabel,
            java.util.function.Predicate<String> isValueCandidate
    ) {
        if (allLines == null || allLines.isEmpty()) return "";

        OcrNewLine label = null;
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (isLabel.test(low)) {
                label = l;
                break;
            }
        }
        if (label == null) return "";

        int page = label.getPage();
        int top = label.getTop();
        int right = label.getRight();
        int bestDx = Integer.MAX_VALUE;
        String best = "";

        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            if (l.getPage() != page) continue;
            // same row band
            if (Math.abs(l.getTop() - top) > 14) continue;
            if (l.getLeft() <= right + 5) continue;

            String t = oneLine(l.getText()).trim();
            if (t.isBlank()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (!isValueCandidate.test(low)) continue;

            int dx = l.getLeft() - right;
            if (dx < bestDx) {
                bestDx = dx;
                best = t;
            }
        }
        return best == null ? "" : best.trim();
    }

    private static Map<String, String> extractPurchaseOrderHeaderFieldsFromPdfBytes(byte[] pdfBytes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (pdfBytes == null || pdfBytes.length == 0) return out;

        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            if (doc.getNumberOfPages() <= 0) return out;

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = nvl(stripper.getText(doc));
            if (text.isBlank()) return out;

            // Keep patterns narrow to avoid accidentally matching other sections.
            Matcher mCg = Pattern.compile("(?is)\\bCustoms\\s+Customer\\s+Group\\s*:\\s*([^\\r\\n]+)").matcher(text);
            if (mCg.find()) {
                String v = nvl(mCg.group(1)).trim();
                if (!v.isBlank()) out.put("Customs Customer Group", v);
            }

            Matcher mPieces = Pattern.compile("(?is)\\bNo\\s+of\\s+Pieces\\s*:\\s*([0-9]{1,9})").matcher(text);
            if (mPieces.find()) {
                String v = nvl(mPieces.group(1)).trim();
                if (!v.isBlank()) out.put("No of Pieces", v);
            }
        } catch (Exception ignore) {
            // ignore
        }

        return out;
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
        Pattern qtyOnlyPat = Pattern.compile("^\\s*(\\d[\\d\\s]{0,15})\\s*$");
        Pattern percentOnlyPat = Pattern.compile("^\\s*(\\d+%|<\\d+%)\\s*$");
        Pattern qtyAtEndPat = Pattern.compile("(\\d[\\d\\s]{0,15})\\s*$");
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
                    rowBottomMax = Math.max(rowTop + 20, Math.min(rowTop + 180, next.getTop() - 2));
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
            String totalQtyInBlock = "";

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
                        .limit(12)
                        .map(rl -> {
                            String bucket = (rl.getLeft() >= pl && rl.getLeft() < ql) ? "PLAN"
                                    : (rl.getLeft() >= ql ? "QTY" : "OTHER");
                            return bucket + "[" + rl.getLeft() + "," + rl.getTop() + "-" + rl.getRight() + "," + rl.getBottom() + "]'" + rl.getText() + "'";
                        })
                        .reduce("", (a, b) -> a.isEmpty() ? b : (a + " | " + b));
                log.debug("[PO-TIME-DELIVERY][DIAG] row={} page={} windowTopBottom=[{}..{}] lines={}",
                        timeOfDelivery, page, rowTopMin, rowBottomMax, truncate(rlDump, 900));
            }

            StringBuilder planBuf = new StringBuilder();
            for (OcrNewLine rl : rowLines) {
                if (rl.getLeft() >= planningLeft && rl.getLeft() < qtyLeft) {
                    String candidate = rl.getText();
                    if (candidate.equalsIgnoreCase("planning markets")) continue;
                    if (candidate.equalsIgnoreCase("total") || candidate.toLowerCase(Locale.ROOT).startsWith("total:")) continue;
                    String candLow = candidate.toLowerCase(Locale.ROOT);
                    if (candLow.contains("quantity per artic") || candLow.contains("article no")
                            || candLow.contains("total quantity")) {
                        continue;
                    }
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
                    // Quantity/% cells should align with the current date row.
                    // If our vertical capture window overlaps the previous row, ignore any qty candidates
                    // that are clearly above the current date baseline to prevent repeating values.
                    if (rl.getTop() < rowTop - 10) {
                        continue;
                    }
                    String qtyCand = rl.getText();
                    if (qtyCand != null) qtyCand = qtyCand.trim();

                    if (qtyCand != null && !qtyCand.isBlank()) {
                        Matcher qpMatcher = qtyPercentPat.matcher(qtyCand);
                        if (qpMatcher.find()) {
                            quantity = qpMatcher.group(1).replaceAll("\\s+", " ").trim();
                            percentTotalQty = qpMatcher.group(2).trim();
                            continue;
                        }

                        // OCR sometimes splits Quantity and % into separate lines.
                        // Example: one line "402" and another line "34%".
                        Matcher qOnly = qtyOnlyPat.matcher(qtyCand);
                        if (quantity.isBlank() && qOnly.find()) {
                            quantity = qOnly.group(1).replaceAll("\\s+", " ").trim();
                            continue;
                        }
                        Matcher pOnly = percentOnlyPat.matcher(qtyCand);
                        if (percentTotalQty.isBlank() && pOnly.find()) {
                            percentTotalQty = pOnly.group(1).trim();
                        }
                    }
                }

                // Capture Total quantity line that can appear in the same vertical block.
                // Example: "Total: 28" might end up in the planning column region.
                String maybeTotal = rl.getText();
                if (maybeTotal != null) {
                    String lowTotal = maybeTotal.toLowerCase(Locale.ROOT).trim();
                    if (lowTotal.startsWith("total:")) {
                        String tail = maybeTotal.substring(Math.min(maybeTotal.length(), 6)).trim();
                        Matcher mt = qtyAtEndPat.matcher(tail);
                        if (mt.find()) {
                            totalQtyInBlock = mt.group(1).replaceAll("\\s+", " ").trim();
                        }
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

            // Final fallback: if the entire row is a single OCR line (date + planning + qty on one line),
            // parse planning markets and qty/percent directly from the text after the date match.
            if (planningMarkets.isBlank() || quantity.isBlank()) {
                String afterDateText = line.substring(dm.end()).trim();
                if (!afterDateText.isBlank()) {
                    Matcher inlineQp = qtyPercentPat.matcher(afterDateText);
                    if (inlineQp.find()) {
                        if (quantity.isBlank()) {
                            quantity = inlineQp.group(1).replaceAll("\\s+", " ").trim();
                            percentTotalQty = inlineQp.group(2).trim();
                        }
                        if (planningMarkets.isBlank()) {
                            String inlinePlanning = afterDateText.substring(0, inlineQp.start()).trim();
                            List<String> inlineTokens = extractPlanningTokens.apply(inlinePlanning);
                            if (!inlineTokens.isEmpty()) {
                                planningMarkets = String.join(", ", inlineTokens);
                            } else if (!inlinePlanning.isBlank()) {
                                planningMarkets = inlinePlanning;
                            }
                        }
                    } else {
                        // Handle inline qty without percent: "BE (PMSEU) 28"
                        Matcher inlineQtyEnd = qtyAtEndPat.matcher(afterDateText);
                        if (inlineQtyEnd.find()) {
                            String qtyStr = inlineQtyEnd.group(1).replaceAll("\\s+", " ").trim();
                            String beforeQty = afterDateText.substring(0, inlineQtyEnd.start()).trim();
                            if (quantity.isBlank() && !qtyStr.isBlank()) {
                                quantity = qtyStr;
                            }
                            if (planningMarkets.isBlank() && !beforeQty.isBlank()) {
                                List<String> inlineTokens = extractPlanningTokens.apply(beforeQty);
                                if (!inlineTokens.isEmpty()) {
                                    planningMarkets = String.join(", ", inlineTokens);
                                } else {
                                    planningMarkets = beforeQty;
                                }
                            }
                        } else if (planningMarkets.isBlank()) {
                            // No qty/percent found but there may still be planning tokens
                            List<String> inlineTokens = extractPlanningTokens.apply(afterDateText);
                            if (!inlineTokens.isEmpty()) {
                                planningMarkets = String.join(", ", inlineTokens);
                            }
                        }
                    }
                }
            }

            // If this section has only one row and we have a matching Total, percent is 100%.
            if (percentTotalQty.isBlank() && !quantity.isBlank() && !totalQtyInBlock.isBlank()) {
                if (quantity.replaceAll("\\s+", "").equals(totalQtyInBlock.replaceAll("\\s+", ""))) {
                    percentTotalQty = "100%";
                }
            }

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

        if (out.size() == 1) {
            Map<String, String> only = out.get(0);
            if (only != null) {
                String q = only.get("quantity");
                String p = only.get("percentTotalQty");
                if ((p == null || p.isBlank()) && (q != null && !q.isBlank())) {
                    only.put("percentTotalQty", "100%");
                }
            }
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
                    // Strip any trailing qty+percent pattern and keep the planning part.
                    String tail = s.substring(dm.end()).trim();
                    if (!tail.isBlank()) {
                        Matcher tailQp = qtyPercentPat.matcher(tail);
                        if (tailQp.find()) {
                            String planPart = tail.substring(0, tailQp.start()).trim();
                            if (!planPart.isBlank()) {
                                planBuf.append(planPart);
                            }
                        } else {
                            planBuf.append(tail);
                        }
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
     * Columns: articleNo, hmColourCode, ptArticleNumber, colour, graphicalAppearance, cost, qtyArticle
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
        Pattern costOnlyPat = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})$");
        Pattern qtyOnlyPat = Pattern.compile("^[\\d\\s]{2,}$");
        Pattern optionNoTailPat = Pattern.compile("\\b([0-9A-Z]{3,})\\s*\\((V\\d+)\\)\\s*$");
        Pattern trailingGaPat = Pattern.compile("^(.*?)(?:\\s+)(All\\s+over\\s+pattern|Solid|Placement\\s+print|Stripe|Check)\\s*$", Pattern.CASE_INSENSITIVE);
        Pattern articleStartPat = Pattern.compile("^(\\d{3})\\s+([\\d\\-]+)\\s*(.*)$");
        Pattern hmColourPrefixPat = Pattern.compile("^\\d{2}-\\d{2}$");
        Pattern hmSplitFixPat = Pattern.compile("^(\\d)\\s+(.*)$");
        Pattern hmSplitFixCompactPat = Pattern.compile("^(\\d)(\\d{2})(.*)$");

        String lastColourLine = "";
        String lastColourNameFromLabel = "";
        String lastGraphicalFromLabel = "";
        String pendingArticleNo = "";
        String pendingHmColourCode = "";
        String pendingMiddle = "";
        String pendingCost = "";

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

            if (low.startsWith("colour name:")) {
                String v = line.substring(Math.min(line.length(), "Colour Name:".length())).trim();
                if (!v.isBlank()) {
                    lastColourNameFromLabel = v;
                }
                continue;
            }
            if (low.startsWith("graphical appearance:")) {
                String v = line.substring(Math.min(line.length(), "Graphical Appearance:".length())).trim();
                if (!v.isBlank()) {
                    lastGraphicalFromLabel = v;
                }
                continue;
            }

            // Don't absorb cost-only ("5.34 USD") or qty-only ("9 554") lines into pendingMiddle —
            // they belong to the 3-line row reassembly handler below. Without this guard, the cost/qty
            // lines pollute pendingMiddle and prevent the row from ever being emitted, leaving
            // pendingMiddle to be reused on the next article row (causing "Colour Option No ..." pollution).
            boolean isCostOnly = !pendingArticleNo.isBlank() && costOnlyPat.matcher(line).matches();
            boolean isQtyOnly = !pendingArticleNo.isBlank() && !pendingCost.isBlank()
                    && qtyOnlyPat.matcher(line).matches()
                    && line.replaceAll("[^0-9]", "").length() >= 2;

            if (!line.matches("^\\d{3}\\b.*")
                    && !costQtyPat.matcher(line).find()
                    && !isCostOnly
                    && !isQtyOnly
                    && !low.contains("article no")
                    && !low.contains("colour code")
                    && !low.contains("qty/article")
                    && !low.contains("cost")) {
                if (!pendingArticleNo.isBlank()) {
                    pendingMiddle = pendingMiddle.isBlank() ? line : (pendingMiddle + " " + line);
                } else {
                    if (!line.matches("^[A-Z]{2}$")) {
                        // Avoid poisoning colour with table header artefacts like "Colour Option No".
                        // OCR sometimes misreads it (e.g. "Colour Optior"), so use a broader heuristic.
                        if (low.startsWith("colour") && low.length() <= 25) {
                            continue;
                        }
                        // If we recently saw a 'Colour Name:' line and this is a short continuation token
                        // (e.g. 'Light'), append it to complete the colour.
                        if (!lastColourNameFromLabel.isBlank() && line.matches("^[A-Za-z]{2,15}$")
                                && (lastGraphicalFromLabel.isBlank() || lastGraphicalFromLabel.length() < 2)) {
                            lastColourNameFromLabel = (lastColourNameFromLabel + " " + line).replaceAll("\\s+", " ").trim();
                            continue;
                        }
                        lastColourLine = lastColourLine.isBlank() ? line : (lastColourLine + " " + line);
                    }
                }
                continue;
            }

            Matcher start = articleStartPat.matcher(line);
            if (start.find()) {
                pendingArticleNo = start.group(1).trim();
                pendingHmColourCode = start.group(2).trim();
                pendingMiddle = (start.group(3) == null ? "" : start.group(3).trim());
                pendingCost = "";
                continue;
            }

            // Support tables where cost and qty are on separate lines (common with hOCR output):
            // line A: "001 12-220 02" + "White Dusty Light 1GPOO (V2)"
            // line B: "5.34 USD"
            // line C: "9 554"
            if (!pendingArticleNo.isBlank()) {
                Matcher costOnly = costOnlyPat.matcher(line);
                if (costOnly.find()) {
                    pendingCost = costOnly.group(1).trim() + " " + costOnly.group(2).trim();
                    continue;
                }
                String qtyDigits = line.replaceAll("[^0-9]", "");
                if (!pendingCost.isBlank() && qtyOnlyPat.matcher(line).matches() && qtyDigits.length() >= 2) {
                    String cost = pendingCost;
                    String qtyArticle = line.replaceAll("\\s+", " ").trim();

                    String ptArticleNumber = "";
                    String graphicalAppearance = "";
                    String optionNo = "";
                    String colour = lastColourLine.trim();

                    String middle = pendingMiddle == null ? "" : pendingMiddle;
                    String[] parts = middle.trim().isEmpty() ? new String[0] : middle.trim().split("\\s+");
                    if (parts.length >= 1) {
                        ptArticleNumber = parts[0];
                    }

                    String remainder = "";
                    if (!middle.isBlank() && !ptArticleNumber.isBlank()) {
                        remainder = middle.trim();
                        if (remainder.startsWith(ptArticleNumber)) {
                            remainder = remainder.substring(ptArticleNumber.length()).trim();
                        } else if (parts.length >= 2) {
                            StringBuilder b = new StringBuilder();
                            for (int pi = 1; pi < parts.length; pi++) {
                                if (b.length() > 0) b.append(' ');
                                b.append(parts[pi]);
                            }
                            remainder = b.toString().trim();
                        }
                    }

                    if (!remainder.isBlank()) {
                        Matcher optTail = optionNoTailPat.matcher(remainder);
                        if (optTail.find()) {
                            optionNo = (optTail.group(1).trim() + " (" + optTail.group(2).trim() + ")").trim();
                            colour = remainder.substring(0, optTail.start()).replaceAll("\\s+", " ").trim();
                        } else {
                            Matcher gaTail = trailingGaPat.matcher(remainder);
                            if (gaTail.find()) {
                                colour = nvl(gaTail.group(1)).replaceAll("\\s+", " ").trim();
                                graphicalAppearance = nvl(gaTail.group(2)).replaceAll("\\s+", " ").trim();
                            } else {
                                colour = remainder.replaceAll("\\s+", " ").trim();
                            }
                        }
                    }

                    if (!optionNo.isBlank()) {
                        String lowColour = nvl(colour).toLowerCase(Locale.ROOT);
                        boolean looksPolluted = lowColour.startsWith("colour") || lowColour.contains("article no") || lowColour.matches(".*\\b\\d{3}\\b.*");
                        if (looksPolluted) {
                            String midAll = nvl(pendingMiddle).replaceAll("\\s+", " ").trim();
                            if (!midAll.isBlank()) {
                                String a = Pattern.quote(nvl(pendingArticleNo).trim());
                                String h = Pattern.quote(nvl(pendingHmColourCode).trim());
                                String ptn = Pattern.quote(nvl(ptArticleNumber).trim());
                                String opt = Pattern.quote(nvl(optionNo).trim());
                                Pattern rowSig = Pattern.compile("\\b" + a + "\\s+" + h + "\\s+" + ptn + "\\s+(.*?)\\s+" + opt + "\\b");
                                Matcher rm = rowSig.matcher(midAll);
                                if (rm.find()) {
                                    String c = nvl(rm.group(1)).replaceAll("\\s+", " ").trim();
                                    if (!c.isBlank()) {
                                        colour = c;
                                    }
                                }
                            }
                        }
                    }

                    if (!lastColourNameFromLabel.isBlank()) {
                        colour = lastColourNameFromLabel.trim();
                    }
                    if (!lastGraphicalFromLabel.isBlank()) {
                        graphicalAppearance = lastGraphicalFromLabel.trim();
                    }

                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("articleNo", pendingArticleNo);
                    row.put("hmColourCode", pendingHmColourCode);
                    row.put("ptArticleNumber", ptArticleNumber);
                    row.put("colour", colour);
                    row.put("graphicalAppearance", graphicalAppearance);
                    row.put("optionNo", optionNo);
                    row.put("cost", cost);
                    row.put("qtyArticle", qtyArticle);
                    out.add(row);
                    log.info("[PO-QTY-ARTICLE] Extracted row: {} | {} | {} | {} | {} | {} | {}",
                            pendingArticleNo, pendingHmColourCode, ptArticleNumber, colour, (optionNo.isBlank() ? graphicalAppearance : optionNo), cost, qtyArticle);

                    pendingArticleNo = "";
                    pendingHmColourCode = "";
                    pendingMiddle = "";
                    pendingCost = "";
                    lastColourLine = "";
                    lastColourNameFromLabel = "";
                    lastGraphicalFromLabel = "";
                    continue;
                }
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
                String graphicalAppearance = "";
                String optionNo = "";
                String colour = lastColourLine.trim();

                String middle = combinedMiddle;
                if (middle == null) middle = "";
                String[] parts = middle.trim().isEmpty() ? new String[0] : middle.trim().split("\\s+");
                if (parts.length >= 1) {
                    ptArticleNumber = parts[0];
                }

                // For templates where the Colour/OptionNo are in-table (e.g. "Colour Option No" header),
                // derive colour/optionNo from the middle string (everything after ptArticleNumber).
                String remainder = "";
                if (middle != null && !middle.isBlank() && !ptArticleNumber.isBlank()) {
                    remainder = middle.trim();
                    if (remainder.startsWith(ptArticleNumber)) {
                        remainder = remainder.substring(ptArticleNumber.length()).trim();
                    } else {
                        // If OCR inserted extra spacing or the first token logic drifted, fallback to joining from parts[1].
                        if (parts.length >= 2) {
                            StringBuilder b = new StringBuilder();
                            for (int pi = 1; pi < parts.length; pi++) {
                                if (b.length() > 0) b.append(' ');
                                b.append(parts[pi]);
                            }
                            remainder = b.toString().trim();
                        }
                    }
                }

                if (!remainder.isBlank()) {
                    Matcher optTail = optionNoTailPat.matcher(remainder);
                    if (optTail.find()) {
                        optionNo = (optTail.group(1).trim() + " (" + optTail.group(2).trim() + ")").trim();
                        colour = remainder.substring(0, optTail.start()).replaceAll("\\s+", " ").trim();
                    } else {
                        Matcher gaTail = trailingGaPat.matcher(remainder);
                        if (gaTail.find()) {
                            colour = nvl(gaTail.group(1)).replaceAll("\\s+", " ").trim();
                            graphicalAppearance = nvl(gaTail.group(2)).replaceAll("\\s+", " ").trim();
                        } else {
                            // If we only have the colour token(s) in remainder.
                            colour = remainder.replaceAll("\\s+", " ").trim();
                        }
                    }
                }

                // If colour got polluted by header/table concatenation (e.g. "Colour Option No 001 ..."),
                // re-extract colour by locating the specific row signature in the full middle string.
                // This is a defensive fix for OCR layouts where multiple rows collapse into one logical line.
                if (!optionNo.isBlank()) {
                    String lowColour = nvl(colour).toLowerCase(Locale.ROOT);
                    boolean looksPolluted = lowColour.startsWith("colour") || lowColour.contains("article no") || lowColour.matches(".*\\b\\d{3}\\b.*");
                    if (looksPolluted) {
                        String midAll = nvl(pendingMiddle).replaceAll("\\s+", " ").trim();
                        if (!midAll.isBlank()) {
                            String a = Pattern.quote(nvl(pendingArticleNo).trim());
                            String h = Pattern.quote(nvl(pendingHmColourCode).trim());
                            String ptn = Pattern.quote(nvl(ptArticleNumber).trim());
                            String opt = Pattern.quote(nvl(optionNo).trim());
                            Pattern rowSig = Pattern.compile("\\b" + a + "\\s+" + h + "\\s+" + ptn + "\\s+(.*?)\\s+" + opt + "\\b");
                            Matcher rm = rowSig.matcher(midAll);
                            if (rm.find()) {
                                String c = nvl(rm.group(1)).replaceAll("\\s+", " ").trim();
                                if (!c.isBlank()) {
                                    colour = c;
                                }
                            }
                        }
                    }
                }

                // Prefer the labelled values when present (stable on H&M docs).
                if (!lastColourNameFromLabel.isBlank()) {
                    colour = lastColourNameFromLabel.trim();
                }
                if (!lastGraphicalFromLabel.isBlank()) {
                    graphicalAppearance = lastGraphicalFromLabel.trim();
                }

                Map<String, String> row = new LinkedHashMap<>();
                row.put("articleNo", pendingArticleNo);
                row.put("hmColourCode", pendingHmColourCode);
                row.put("ptArticleNumber", ptArticleNumber);
                row.put("colour", colour);
                row.put("graphicalAppearance", graphicalAppearance);
                row.put("optionNo", optionNo);
                row.put("cost", cost);
                row.put("qtyArticle", qtyArticle);
                out.add(row);
                log.info("[PO-QTY-ARTICLE] Extracted row: {} | {} | {} | {} | {} | {} | {}",
                        pendingArticleNo, pendingHmColourCode, ptArticleNumber, colour, (optionNo.isBlank() ? graphicalAppearance : optionNo), cost, qtyArticle);

                pendingArticleNo = "";
                pendingHmColourCode = "";
                pendingMiddle = "";
                lastColourLine = "";
                lastColourNameFromLabel = "";
                lastGraphicalFromLabel = "";
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
            Pattern articlePrefixOnlyPat = Pattern.compile("^\\d{3}\\s+\\d{2}-\\d{3}\\s+\\d{2}\\b.*$");
            Pattern costQtyPat = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+([A-Z]{3})\\s+([\\d\\s]+)$");
            Pattern optionNoPat = Pattern.compile("\\b([0-9A-Z]{3,})\\s*\\((V\\d+)\\)\\s*$");
            Pattern trailingGaPat = Pattern.compile("^(.*?)(?:\\s+)(All\\s+over\\s+pattern|Solid|Placement\\s+print|Stripe|Check)\\s*$", Pattern.CASE_INSENSITIVE);

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

                String pendingPrefixLine = "";
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

                    // Some PDFs split the row into two lines:
                    // - line A: "001 12-220 02 White Dusty Light 1GPOO (V2)"
                    // - line B: "5.34 USD 9 554"
                    boolean isPrefixOnly = articlePrefixOnlyPat.matcher(line).matches() && !costQtyPat.matcher(line).find();
                    if (isPrefixOnly) {
                        pendingPrefixLine = line;
                        continue;
                    }

                    boolean hasCostQty = costQtyPat.matcher(line).find();
                    boolean isFullRow = line.matches("^\\d{3}\\b.*") && hasCostQty;
                    if (!isFullRow && hasCostQty && !pendingPrefixLine.isBlank()) {
                        // Combine two-line format
                        line = (pendingPrefixLine + " " + line).replaceAll("\\s+", " ").trim();
                        low = line.toLowerCase(Locale.ROOT);
                        pendingPrefixLine = "";
                        isFullRow = line.matches("^\\d{3}\\b.*") && costQtyPat.matcher(line).find();
                    }

                    if (!isFullRow) continue;

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
                    String graphicalAppearance = "";
                    String optionNo = "";
                    String rem = remainder.replaceAll("\\s+", " ").trim();

                    // Some templates have an explicit Option No column like: "White Dusty Light 1GPOO (V2)".
                    // Prefer capturing that into optionNo; otherwise treat tail tokens as GraphicalAppearance.
                    Matcher optM = optionNoPat.matcher(rem);
                    if (optM.find()) {
                        optionNo = (optM.group(1).trim() + " (" + optM.group(2).trim() + ")").trim();
                        colour = rem.substring(0, optM.start()).replaceAll("\\s+", " ").trim();
                    } else {
                        Matcher gaM = trailingGaPat.matcher(rem);
                        if (gaM.find()) {
                            colour = nvl(gaM.group(1)).replaceAll("\\s+", " ").trim();
                            graphicalAppearance = nvl(gaM.group(2)).replaceAll("\\s+", " ").trim();
                        } else {
                            colour = rem;
                        }
                    }

                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("page", String.valueOf(page));
                    row.put("articleNo", articleNo);
                    row.put("hmColourCode", hmColourCode);
                    row.put("ptArticleNumber", ptArticleNumber);
                    row.put("colour", colour);
                    row.put("graphicalAppearance", graphicalAppearance);
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

        List<OcrNewLine> sorted = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l == null || l.getText() == null) continue;
            sorted.add(l);
        }
        sorted.sort(Comparator
                .comparingInt(OcrNewLine::getPage)
                .thenComparingInt(OcrNewLine::getTop)
                .thenComparingInt(OcrNewLine::getLeft));

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : sorted) {
            texts.add(oneLine(l.getText()).trim());
        }
        // Find "Invoice Average Price" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            boolean looksLikeHeader = (low.contains("invoice average") || low.contains("invoice avg"))
                    && low.contains("country");
            if (looksLikeHeader) {
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
        Pattern countryCodeAnyPat = Pattern.compile("\\b([A-Z]{2})\\b");

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
                row.put("_src", "sameLine");
                out.add(row);
                log.info("[PO-INVOICE-PRICE] Extracted row: {} | {}", row.get("invoiceAveragePrice"), row.get("country"));
                lastPrice = "";
                continue;
            }

            Matcher priceOnly = priceOnlyPat.matcher(line);
            Matcher countryOnly = countryOnlyPat.matcher(line);
            if (priceOnly.find()) {
                // If we see a new price while the previous price is still waiting for a country,
                // do not lose it. Emit it with blank country.
                if (!lastPrice.isEmpty()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("invoiceAveragePrice", lastPrice);
                    row.put("country", "");
                    row.put("_src", "blank");
                    out.add(row);
                    log.info("[PO-INVOICE-PRICE] Extracted row (country missing): {} |", lastPrice);
                }
                lastPrice = line;
                continue;
            }

            String cleanedCountryLine = line.replaceAll("\\s+", " ").replaceAll(",\\s*$", "").trim();
            // Follow Time of Delivery planning-markets behavior: if the OCR line is in the form
            // "<something>, <country tokens...>", keep the whole trailing segment after the first comma.
            int firstComma = cleanedCountryLine.indexOf(',');
            if (firstComma >= 0) {
                String afterComma = cleanedCountryLine.substring(firstComma + 1).trim();
                if (!afterComma.isBlank() && countryCodeAnyPat.matcher(afterComma).find()) {
                    cleanedCountryLine = afterComma;
                }
            }

            boolean isCountry = countryOnlyPat.matcher(cleanedCountryLine).find()
                    || countryListPat.matcher(cleanedCountryLine).find()
                    || (cleanedCountryLine.contains(",") && countryCodeAnyPat.matcher(cleanedCountryLine).find());
            if (isCountry && !lastPrice.isEmpty()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("invoiceAveragePrice", lastPrice);
                row.put("country", cleanedCountryLine);
                row.put("_src", "paired");
                out.add(row);
                log.info("[PO-INVOICE-PRICE] Extracted row: {} | {}", lastPrice, cleanedCountryLine);
                lastPrice = "";
            }
        }

        // Flush trailing price if the country never appeared inside the scan window.
        if (!lastPrice.isEmpty()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("invoiceAveragePrice", lastPrice);
            row.put("country", "");
            row.put("_src", "blank");
            out.add(row);
            log.info("[PO-INVOICE-PRICE] Extracted row (country missing at end): {} |", lastPrice);
        }
        log.info("[PO-INVOICE-PRICE] Total rows extracted: {}", out.size());
        return out;
    }

    private static void fillMissingPurchaseOrderInvoiceAvgPriceCountriesFromOtherPages(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) return;

        Pattern singleCountryPat = Pattern.compile("^[A-Z]{2}$");
        Map<String, String> countryByPrice = new LinkedHashMap<>();
        Map<String, Integer> scoreByPrice = new LinkedHashMap<>();
        Map<String, Integer> srcRankByPrice = new LinkedHashMap<>();
        Map<String, Integer> pageByPrice = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            if (r == null) continue;
            String p = nvl(r.get("invoiceAveragePrice")).trim();
            if (p.isBlank()) continue;
            String c = nvl(r.get("country")).trim();
            if (c.isBlank()) continue;
            if (!singleCountryPat.matcher(c).matches()) continue;

            String src = nvl(r.get("_src")).trim();
            // Only accept strong sources as authoritative candidates for cross-page filling.
            // This prevents polluting the fill-map with countries derived from Terms or other heuristics.
            if (!(src.equals("sameLine") || src.equals("paired"))) continue;

            int srcRank = src.equals("sameLine") ? 2 : 1;
            int score = 0;
            if (src.equals("sameLine")) score += 1000;
            else if (src.equals("paired")) score += 800;
            else if (src.equals("filled")) score += 200;
            else if (src.equals("terms")) score += 50;
            else score += 100;

            int page = 0;
            try {
                page = Integer.parseInt(nvl(r.get("page")).trim());
            } catch (Exception ignore) {
            }
            // Prefer later page when source score ties (later pages tend to contain the
            // consolidated/complete table, while earlier detail pages may include artefacts).
            score += Math.max(0, page);

            Integer best = scoreByPrice.get(p);
            Integer bestPage = pageByPrice.get(p);
            if (best == null || score > best || (score == best && bestPage != null && page > bestPage)) {
                scoreByPrice.put(p, score);
                countryByPrice.put(p, c);
                srcRankByPrice.put(p, srcRank);
                pageByPrice.put(p, page);
            }
        }

        if (countryByPrice.isEmpty()) return;

        for (Map<String, String> r : rows) {
            if (r == null) continue;
            String p = nvl(r.get("invoiceAveragePrice")).trim();
            if (p.isBlank()) continue;
            String c = nvl(r.get("country")).trim();

            String bestCountry = countryByPrice.get(p);
            Integer bestRank = srcRankByPrice.get(p);
            if (bestCountry == null || bestCountry.isBlank() || bestRank == null) continue;

            String src = nvl(r.get("_src")).trim();
            int curRank = src.equals("sameLine") ? 2 : (src.equals("paired") ? 1 : 0);
            boolean curSingle = !c.isBlank() && singleCountryPat.matcher(c).matches();

            boolean shouldOverwrite = false;
            if (c.isBlank()) shouldOverwrite = true;
            // If we have a stronger source for this price (e.g. sameLine on another page),
            // propagate it to weaker rows even if they currently have a value.
            else if (bestRank > curRank) shouldOverwrite = true;
            // If both are strong but differ, prefer sameLine over paired.
            else if (bestRank == curRank && curRank == 1 && curSingle && !bestCountry.equalsIgnoreCase(c) && bestRank == 2) shouldOverwrite = true;

            if (shouldOverwrite) {
                r.put("country", bestCountry);
                r.put("_src", "filled");
                log.info("[PO-INVOICE-PRICE] Filled/overrode country from other pages: {} | {}", p.trim(), bestCountry);
            }
        }
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
