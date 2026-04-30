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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
            for (int i = 0; i < pageImages.size(); i++) {
                BufferedImage pageImage = removeColoredBorders(pageImages.get(i));
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
            List<Map<String, String>> poQuantityPerArticle = extractPurchaseOrderQuantityPerArticle(allLines);
            List<Map<String, String>> poInvoiceAvgPrice = extractPurchaseOrderInvoiceAvgPrice(allLines);

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

    private static String oneLine(String s) {
        return safe(s).replaceAll("\\s+", " ").trim();
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
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("terms of delivery")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return null;

        // Collect lines after header until next section (Time of Delivery, Quantity per Article, etc.)
        StringBuilder sb = new StringBuilder();
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 15); i++) {
            String line = texts.get(i).trim();
            String low = line.toLowerCase(Locale.ROOT);
            // Stop at next section header
            if (low.contains("time of delivery") || low.contains("quantity per artic")
                    || low.contains("planning market") || low.contains("% total")) break;
            if (line.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(line);
        }

        String block = sb.toString().trim();
        if (block.isEmpty()) return null;

        // Try to extract the key transport term: "Transport by Sea/Air/..."
        Pattern transportPat = Pattern.compile("(?i)(Transport\\s+by\\s+\\S+(?:\\s*,\\s*[^.]+)?)");
        Matcher m = transportPat.matcher(block);
        if (m.find()) {
            return m.group(1).trim();
        }

        // Fallback: look for Incoterms (FOB, FCA, CIF, etc.)
        Pattern incoPat = Pattern.compile("(?i)\\b(FOB|FCA|CIF|CFR|EXW|DAP|DDP)\\b.*");
        Matcher im = incoPat.matcher(block);
        if (im.find()) {
            return im.group(0).trim();
        }

        // Last fallback: return the whole block (trimmed)
        return block.length() > 200 ? block.substring(0, 200) : block;
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

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        // Find "Time of Delivery" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("time of delivery")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return out;

        // Scan lines after header for date patterns + planning markets + quantity + percentage
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 40); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);
            
            // Stop at next major section
            if (low.contains("quantity per artic") || low.contains("article no") 
                    || low.contains("total quantity") || low.startsWith("total:")) break;

            // Match date pattern
            Matcher dm = DATE_PATTERN.matcher(line);
            if (!dm.find()) continue;

            String timeOfDelivery = dm.group(0).trim();
            String remainder = line.substring(dm.end()).trim();

            // Extract planning markets (PM codes or country codes)
            String planningMarkets = "";
            String quantity = "";
            String percentTotalQty = "";

            // Try to extract planning markets (e.g., "BE (PMSEU)", "KR (PM-KR), ID (PM-ID)")
            Matcher pmMatcher = DEST_COUNTRY_PAT.matcher(remainder);
            List<String> pmCodes = new ArrayList<>();
            while (pmMatcher.find()) {
                pmCodes.add(pmMatcher.group().trim());
            }
            if (!pmCodes.isEmpty()) {
                planningMarkets = String.join(", ", pmCodes);
            }

            // Extract quantity and percentage from end of line
            // Pattern: "2 765 8%" or "16 587 45%"
            Pattern qtyPercentPat = Pattern.compile("(\\d[\\d\\s]+)\\s+(\\d+%|<\\d+%)\\s*$");
            Matcher qpMatcher = qtyPercentPat.matcher(remainder);
            if (qpMatcher.find()) {
                quantity = qpMatcher.group(1).replaceAll("\\s+", " ").trim();
                percentTotalQty = qpMatcher.group(2).trim();
            }

            Map<String, String> row = new LinkedHashMap<>();
            row.put("timeOfDelivery", timeOfDelivery);
            row.put("planningMarkets", planningMarkets);
            row.put("quantity", quantity);
            row.put("percentTotalQty", percentTotalQty);
            out.add(row);
        }

        return out;
    }

    /**
     * Extract "Quantity per Article" table from Purchase Order PDF.
     * Columns: articleNo, hmColourCode, ptArticleNumber, colour, optionNo, cost, qtyArticle
     */
    private static List<Map<String, String>> extractPurchaseOrderQuantityPerArticle(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        // Find "Quantity per Article" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("quantity per artic")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return out;

        // Pattern to match article data row:
        // "001 22-216 02 g 2GPOO (V5) 6.92 USD 36 795"
        // or "001 22-21 6 02 6.85 USD 36 795"
        Pattern articlePat = Pattern.compile("^(\\d{3})\\s+([\\d\\-]+)\\s+(.+?)\\s+(\\d+\\.\\d+\\s+[A-Z]{3})\\s+([\\d\\s]+)$");

        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 50); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);

            // Stop at next section
            if (low.contains("total quantity") || low.contains("invoice average")
                    || low.contains("sales sample")) break;

            Matcher m = articlePat.matcher(line);
            if (m.find()) {
                String articleNo = m.group(1).trim();
                String hmColourCode = m.group(2).trim();
                String middle = m.group(3).trim();
                String cost = m.group(4).trim();
                String qtyArticle = m.group(5).replaceAll("\\s+", " ").trim();

                // Parse middle part: "02 g 2GPOO (V5)" or "6 02"
                // Try to extract PT Article Number, Colour, Option No
                String ptArticleNumber = "";
                String colour = "";
                String optionNo = "";

                // Simple heuristic: split by spaces and look for patterns
                String[] parts = middle.split("\\s+");
                if (parts.length >= 2) {
                    ptArticleNumber = parts[0] + (parts.length > 1 ? " " + parts[1] : "");
                    if (middle.contains("(")) {
                        int parenIdx = middle.indexOf('(');
                        optionNo = middle.substring(parenIdx).replaceAll("[()]", "").trim();
                    }
                }

                Map<String, String> row = new LinkedHashMap<>();
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

        return out;
    }

    /**
     * Extract "Invoice Average Price" table from Purchase Order PDF.
     * Columns: invoiceAveragePrice, country
     */
    private static List<Map<String, String>> extractPurchaseOrderInvoiceAvgPrice(List<OcrNewLine> allLines) {
        List<Map<String, String>> out = new ArrayList<>();
        if (allLines == null || allLines.isEmpty()) return out;

        List<String> texts = new ArrayList<>();
        for (OcrNewLine l : allLines) {
            if (l != null && l.getText() != null) {
                texts.add(oneLine(l.getText()).trim());
            }
        }

        // Find "Invoice Average Price" header
        int headerIdx = -1;
        for (int i = 0; i < texts.size(); i++) {
            String low = texts.get(i).toLowerCase(Locale.ROOT);
            if (low.contains("invoice average price")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return out;

        // Pattern to match price + currency: "113364.36 IDR" or "6.85 USD"
        Pattern pricePat = Pattern.compile("^([\\d\\.,]+)\\s+([A-Z]{2,3})$");
        // Pattern to match country code: "ID", "JP", "TH", "VN", etc.
        Pattern countryPat = Pattern.compile("^([A-Z]{2})$");

        String lastPrice = "";
        for (int i = headerIdx + 1; i < Math.min(texts.size(), headerIdx + 20); i++) {
            String line = texts.get(i).trim();
            if (line.isBlank()) continue;
            String low = line.toLowerCase(Locale.ROOT);

            // Stop at next section
            if (low.contains("sales sample") || low.contains("terms of delivery")
                    || low.contains("time of delivery")) break;

            Matcher priceMatcher = pricePat.matcher(line);
            Matcher countryMatcher = countryPat.matcher(line);

            if (priceMatcher.find()) {
                lastPrice = line;
            } else if (countryMatcher.find() && !lastPrice.isEmpty()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("invoiceAveragePrice", lastPrice);
                row.put("country", line);
                out.add(row);
                lastPrice = "";
            }
        }

        return out;
    }
}
