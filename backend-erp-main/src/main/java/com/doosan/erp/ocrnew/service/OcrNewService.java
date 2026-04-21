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

    private static final Pattern SIZE_VALUE_LINE = Pattern.compile("^\\s*([A-Za-z]{1,2})\\s*(?:\\(|\\b).*?\\b(\\d[\\d\\s]{0,12})\\s*$");
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

        Pattern pmPat = Pattern.compile("\\bPM[- ]?[A-Z0-9]{2,}\\b", Pattern.CASE_INSENSITIVE);
        Pattern numPat = Pattern.compile("(?<![A-Za-z])\\d[\\d\\s.,]*");
        Pattern country2Pat = Pattern.compile("^([A-Z]{2})\\b");

        for (int i = startIdx; i < normLines.size(); i++) {
            String line = normLines.get(i);
            String low = line.toLowerCase(Locale.ROOT);
            // Stop on unrelated big sections
            if (low.startsWith("bill of material") || low.startsWith("labels") || low.startsWith("production units")) {
                break;
            }

            Matcher pmM = pmPat.matcher(line);
            if (!pmM.find()) continue;
            String pmCode = pmM.group().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");

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
            m.put("pmCode", pmCode);
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
        // If total missing or blank: set computed sum if we had any numeric values; otherwise default to 0
        String tot = row.get("total");
        if (tot == null || tot.isBlank()) {
            row.put("total", String.valueOf(anyNumeric ? sum : 0));
        }
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
                BufferedImage pageImage = pageImages.get(i);
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
}
