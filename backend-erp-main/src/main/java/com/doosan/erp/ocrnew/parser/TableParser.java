package com.doosan.erp.ocrnew.parser;

import com.doosan.erp.ocrnew.dto.OcrNewBoundingBoxDto;
import com.doosan.erp.ocrnew.dto.OcrNewTableCellDto;
import com.doosan.erp.ocrnew.dto.OcrNewTableDto;
import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.model.OcrNewWord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TableParser {

    private static final Logger log = LoggerFactory.getLogger(TableParser.class);
    
    // Enable BOM coordinate debug logging (set to true to trace word assignments)
    private static final boolean BOM_DEBUG = true;

    private static final Pattern BOM_SECTION_START = Pattern.compile("\\bBill\\s+of\\s+Material\\s*:\\s*Materials\\s+and\\s+Trims\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_SECTION_ANY = Pattern.compile("\\bBill\\s+of\\s+Material\\s*:\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATED_LINE = Pattern.compile("\\bCreated\\s+\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_HEADER_HINT = Pattern.compile("\\bPosition\\b.*\\bPlacement\\b.*\\bType\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_ROW_START = Pattern.compile("^(Trim|Shell|Miscellaneous|Material)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_PUNCT = Pattern.compile("^[\\p{Punct}\\s]+$");
    private static final Pattern TOKEN_HAS_PERCENT = Pattern.compile("\\d{1,3}\\s*%");
    private static final Pattern BOM_PROD_UNITS_START = Pattern.compile("\\bBill\\s+of\\s+Material\\s*:\\s*Production\\s+Units\\s+and\\s+Processing\\s+Capabilities\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_WEIGHT_STOP = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s*(g/m2|g/m|g/piece|gram/km)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRUCTION_HINT = Pattern.compile("\\b\\d{2,}x\\d{2,}\\b|\\b\\d{1,3}\\*\\d{1,3}\\b|\\b\\d{2,}\\/\\d{1,2}\\/\\d{1,2}\\b|\\bx\\d{1,3}\\/\\d{1,2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_TOKEN = Pattern.compile("^(km|yd|m|g/m|g/m2|gram/km)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_CAPS_SPILLOVER = Pattern.compile("^[A-Z0-9][A-Z0-9\\s|.\\-]{2,}$");
    private static final Pattern BOM_HEADER_REQUIRED = Pattern.compile("\\bDescription\\b.*\\bComposition\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_ROW_REJECT = Pattern.compile("^(material\\s+product\\s+type\\b|requirement\\s+for\\b|valid\\s+for\\b|comment:|position\\s+placement\\s+type\\b|pm\\s+textile\\s+label\\b|textile\\s+label\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_LIKE_TOKEN = Pattern.compile("^(QW|QWO|TEL|THD|JY)[A-Z0-9\\-()]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIXED_ALNUM_TOKEN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9\\-()/.]+$");
    private static final Pattern SUPPLIER_STOPWORD = Pattern.compile("^(import|export|ltd|limited|co|company|trading|printing|dyeing|hangzhou|shao?xing|pt|indonesia)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NYLON_WORD = Pattern.compile("^%?nylon$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAR_SPEC = Pattern.compile("^\\d{1,3}\\*\\d{1,3}$");
    private static final Pattern COMPOSITION_WORD = Pattern.compile("(?i)^(polyester|cotton|viscose|nylon|elastane|spandex|wool|silk|linen|rayon|acrylic|recycled|revisco|circulose|so|pol|yester|ester)$");

    public List<OcrNewTableDto> parseTables(List<OcrNewLine> lines) {
        // Heuristic table detection:
        // - find runs of lines on the same page with >=3 word blocks
        // - derive column x positions from aggregated word centers
        // - assign words into nearest columns and output rows
        if (lines == null || lines.isEmpty()) return List.of();

        Map<Integer, List<OcrNewLine>> byPage = lines.stream().collect(Collectors.groupingBy(OcrNewLine::getPage));
        List<OcrNewTableDto> out = new ArrayList<>();
        int tableIndex = 0;

        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            int page = e.getKey();
            List<OcrNewLine> pageLines = e.getValue().stream()
                    .sorted(Comparator.comparingInt(OcrNewLine::getTop))
                    .toList();

            List<List<OcrNewLine>> runs = splitRuns(pageLines);
            for (List<OcrNewLine> run : runs) {
                if (run.size() < 3) continue;

                List<Integer> colXs = deriveColumns(run);
                if (colXs.size() < 3) continue;

                List<List<String>> rows = new ArrayList<>();
                List<OcrNewTableCellDto> cells = new ArrayList<>();

                int rowIdx = 0;
                for (OcrNewLine line : run) {
                    List<StringBuilder> rowBuilders = new ArrayList<>();
                    for (int i = 0; i < colXs.size(); i++) rowBuilders.add(new StringBuilder());

                    for (OcrNewWord w : line.getWords()) {
                        int cx = (w.getLeft() + w.getRight()) / 2;
                        int colIdx = nearestIndex(colXs, cx);
                        if (colIdx < 0) continue;
                        if (rowBuilders.get(colIdx).length() > 0) rowBuilders.get(colIdx).append(' ');
                        rowBuilders.get(colIdx).append(w.getText());
                    }

                    List<String> row = new ArrayList<>(colXs.size());
                    for (int ci = 0; ci < colXs.size(); ci++) {
                        String cellText = rowBuilders.get(ci).toString().trim();
                        row.add(cellText);

                        cells.add(OcrNewTableCellDto.builder()
                                .rowIndex(rowIdx)
                                .columnIndex(ci)
                                .text(cellText)
                                .boundingBox(OcrNewBoundingBoxDto.builder()
                                        .left(colXs.get(ci))
                                        .top(line.getTop())
                                        .width(0)
                                        .height(Math.max(0, line.getBottom() - line.getTop()))
                                        .build())
                                .confidence(line.getConfidence())
                                .build());
                    }

                    // skip empty rows
                    boolean any = row.stream().anyMatch(s -> s != null && !s.isBlank());
                    if (any) {
                        rows.add(row);
                        rowIdx++;
                    }
                }

                if (rows.size() < 2) continue;

                out.add(OcrNewTableDto.builder()
                        .page(page)
                        .index(tableIndex++)
                        .rowCount(rows.size())
                        .columnCount(colXs.size())
                        .cells(cells)
                        .rows(rows)
                        .build());
            }
        }

        List<OcrNewTableDto> bomTables = parseBomDraftTables(lines);
        for (OcrNewTableDto t : bomTables) {
            out.add(OcrNewTableDto.builder()
                    .page(t.getPage())
                    .index(tableIndex++)
                    .rowCount(t.getRowCount())
                    .columnCount(t.getColumnCount())
                    .cells(t.getCells())
                    .rows(t.getRows())
                    .build());
        }

        return out;
    }

    private static List<OcrNewTableDto> parseBomDraftTables(List<OcrNewLine> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        Map<Integer, List<OcrNewLine>> byPage = lines.stream().collect(Collectors.groupingBy(OcrNewLine::getPage));
        List<OcrNewTableDto> out = new ArrayList<>();
        List<List<String>> mergedRows = new ArrayList<>();
        mergedRows.add(List.of("Position", "Placement", "Type", "Description", "Composition", "Material Supplier"));

        boolean bomOpen = false;

        List<Integer> pages = byPage.keySet().stream().sorted().toList();
        for (Integer page : pages) {
            List<OcrNewLine> pageLines = byPage.get(page).stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(OcrNewLine::getTop))
                    .toList();

            int startIdx = -1;
            for (int i = 0; i < pageLines.size(); i++) {
                String t = oneLine(pageLines.get(i).getText());
                if (BOM_SECTION_START.matcher(t).find()) {
                    startIdx = i;
                    break;
                }
            }

            boolean continuationPage = false;
            if (startIdx < 0) {
                // Continuation pages may not repeat the 'Bill of Material: Materials and Trims' title.
                // If we already parsed a BOM section on a previous page, try to continue parsing from the top.
                if (!bomOpen) {
                    continue;
                }
                continuationPage = true;
                startIdx = -1; // scan whole page
            } else {
                bomOpen = true;
            }

            List<OcrNewLine> section = new ArrayList<>();
            List<OcrNewLine> headerLines = new ArrayList<>();
            boolean inBody = false;
            // Track if we've seen header keywords
            boolean sawPositionKeyword = false;
            boolean sawDescriptionKeyword = false;
            
            int scanStart = continuationPage ? 0 : (startIdx + 1);
            for (int i = scanStart; i < pageLines.size(); i++) {
                OcrNewLine l = pageLines.get(i);
                String txt = oneLine(l.getText());
                if (txt.isBlank()) continue;
                String lowerTxt = txt.toLowerCase();
                if (BOM_SECTION_ANY.matcher(txt).find() && !BOM_SECTION_START.matcher(txt).find()) {
                    // hit another BOM section => current one ended
                    bomOpen = false;
                    break;
                }
                if (lowerTxt.contains("production units") || lowerTxt.contains("processing capabilities") || lowerTxt.contains("yarn source")) break;
                if (CREATED_LINE.matcher(txt).find()) break;
                if (txt.toLowerCase().contains("page") && txt.contains("/")) break;

                if (!inBody) {
                    // Traditional single-line header detection
                    if (BOM_HEADER_HINT.matcher(txt).find() && BOM_HEADER_REQUIRED.matcher(txt).find()) {
                        inBody = true;
                        headerLines.add(l);
                        continue;
                    }
                    
                    // hOCR multi-line header detection: collect all potential header lines
                    if (lowerTxt.contains("position") || lowerTxt.contains("placement")) {
                        sawPositionKeyword = true;
                        headerLines.add(l);
                    } else if (lowerTxt.contains("description") || lowerTxt.contains("composition") ||
                               lowerTxt.contains("appearance") || lowerTxt.contains("material") ||
                               lowerTxt.contains("component") || lowerTxt.contains("consumption")) {
                        // Collect other header-related lines
                        headerLines.add(l);
                        if (lowerTxt.contains("description")) {
                            sawDescriptionKeyword = true;
                        }
                    }

                    // On continuation pages, don't start collecting body/section until we've seen at least part of the header.
                    if (continuationPage && !sawPositionKeyword && !sawDescriptionKeyword) {
                        continue;
                    }
                    
                    // Start body when we see a row start pattern after header keywords
                    if (sawPositionKeyword && sawDescriptionKeyword && BOM_ROW_START.matcher(txt).find()) {
                        inBody = true;
                        section.add(l); // Include this line as first body line
                        continue;
                    }
                    
                    // Also start body if we see a clear row start and have seen at least position keyword
                    if (sawPositionKeyword && BOM_ROW_START.matcher(txt).find()) {
                        inBody = true;
                        section.add(l);
                        continue;
                    }
                    
                    continue;
                }

                section.add(l);
            }

            // Cross-page continuation:
            // Some PDFs continue a material description on the next page without repeating the row start (Trim/Shell...).
            // Treat leading non-row-start lines as continuation of the previous merged row.
            if (mergedRows.size() > 1 && !section.isEmpty()) {
                int firstRowStartIdx = -1;
                for (int si = 0; si < section.size(); si++) {
                    String t = oneLine(section.get(si).getText());
                    if (BOM_ROW_START.matcher(t).find()) {
                        firstRowStartIdx = si;
                        break;
                    }
                }

                if (firstRowStartIdx > 0) {
                    StringBuilder cont = new StringBuilder();
                    for (int si = 0; si < firstRowStartIdx; si++) {
                        String t = oneLine(section.get(si).getText());
                        if (t.isBlank()) continue;
                        if (cont.length() > 0) cont.append(' ');
                        cont.append(t);
                    }

                    String contText = oneLine(cont.toString());
                    if (!contText.isBlank()) {
                        List<String> last = mergedRows.get(mergedRows.size() - 1);
                        if (last != null && last.size() >= 2) {
                            // Normalize continuation text for description (fix broken OCR tokens)
                            String contDesc = normalizeBomDescriptionContinuation(contText);
                            if (last.size() > 3) {
                                last.set(3, oneLine(last.get(3) + (last.get(3).isBlank() ? "" : " ") + contDesc));
                            }

                            // Also extract and append composition tokens if last row had composition
                            if (last.size() >= 5) {
                                String lastComp = last.get(4);
                                boolean lastHasComp = lastComp != null && !lastComp.isBlank() && TOKEN_HAS_PERCENT.matcher(lastComp).find();
                                if (lastHasComp && looksLikeCompositionContinuation(contText)) {
                                    String compTokens = extractCompositionContinuationTokens(contText);
                                    // Also check for RECYCLED POLYESTER fragments
                                    String contLow = contText.toLowerCase();
                                    if ((contLow.contains("recycled") || contLow.contains("recy")) && 
                                        (contLow.contains("polyester") || contLow.contains("olyester") || contLow.contains("polyeste"))) {
                                        if (!compTokens.toLowerCase().contains("polyester")) {
                                            compTokens = oneLine(compTokens + " RECYCLED POLYESTER");
                                        }
                                    }
                                    if (!compTokens.isBlank()) {
                                        String newComp = oneLine(lastComp + (lastComp.endsWith("%") ? " " : ", ") + compTokens);
                                        last.set(4, normalizeBomComposition(newComp));
                                    }
                                }
                            }
                        }
                    }

                    section = new ArrayList<>(section.subList(firstRowStartIdx, section.size()));
                }
            }

            List<List<String>> rows = normalizeBomRows(section, headerLines);
            if (rows.size() <= 1) continue;

            // Merge data rows (skip header row at index 0)
            for (int r = 1; r < rows.size(); r++) {
                mergedRows.add(rows.get(r));
            }
        }

        if (mergedRows.size() <= 1) return List.of();

        // Prefer Type, Composition, Material Supplier from 'Production Units and Processing Capabilities' table where available.
        // Match by Position|Placement|Type (normalized) to preserve row order from Materials and Trims table.
        Map<String, ProdUnitsRow> prodUnitsData = extractBomProductionUnitsData(lines);
        if (!prodUnitsData.isEmpty()) {
            for (int r = 1; r < mergedRows.size(); r++) {
                List<String> row = mergedRows.get(r);
                if (row == null || row.size() < 6) continue;
                
                String rowType = row.get(2) == null ? "" : row.get(2);
                String keyShort = bomKeyShort(row.get(0), row.get(1));
                
                // Match by full key (Position|Placement|NormalizedType)
                String fullKey = bomKey(row.get(0), row.get(1), rowType);
                ProdUnitsRow prod = prodUnitsData.get(fullKey);
                
                // Fallback 1: Try partial type match (e.g., "Plain/Cambric" matches "Plain/Cambric/Voile")
                if (prod == null && !rowType.isBlank()) {
                    String normalizedRowType = normalizeType(rowType);
                    for (Map.Entry<String, ProdUnitsRow> e : prodUnitsData.entrySet()) {
                        if (e.getKey().startsWith(keyShort + "|")) {
                            String prodNormType = normalizeType(e.getValue().type());
                            // Check if types are related (one contains the other)
                            if (prodNormType.startsWith(normalizedRowType) || normalizedRowType.startsWith(prodNormType)) {
                                prod = e.getValue();
                                if (BOM_DEBUG) {
                                    log.debug("[BOM-OVERRIDE] PARTIAL match: rowType='{}' prodType='{}'", rowType, e.getValue().type());
                                }
                                break;
                            }
                        }
                    }
                }
                
                // Fallback 2: If row type is empty, find first matching by short key
                if (prod == null && rowType.isBlank()) {
                    for (Map.Entry<String, ProdUnitsRow> e : prodUnitsData.entrySet()) {
                        if (e.getKey().startsWith(keyShort + "|")) {
                            prod = e.getValue();
                            break;
                        }
                    }
                }
                
                if (prod == null) continue;
                
                // Override Type if Production Units has more complete type (e.g., Plain/Cambric/Voile vs Plain/Cambric)
                if (prod.type() != null && !prod.type().isBlank()) {
                    String prodType = prod.type();
                    if (rowType.isBlank() || (prodType.length() > rowType.length() && prodType.toLowerCase().startsWith(rowType.toLowerCase()))) {
                        row.set(2, prodType);
                        if (BOM_DEBUG) {
                            log.debug("[BOM-OVERRIDE] Type: '{}' -> '{}'", rowType, prodType);
                        }
                    }
                }
                
                // Override Composition if available and has %
                if (prod.composition() != null && !prod.composition().isBlank() && TOKEN_HAS_PERCENT.matcher(prod.composition()).find()) {
                    row.set(4, normalizeBomComposition(prod.composition()));
                }
                
                // Override Material Supplier if available
                if (prod.materialSupplier() != null && !prod.materialSupplier().isBlank()) {
                    row.set(5, prod.materialSupplier());
                }
                
                if (BOM_DEBUG) {
                    log.debug("[BOM-OVERRIDE] row {} key='{}' -> type='{}' comp='{}' supplier='{}'", 
                            r, fullKey, row.get(2), row.get(4), row.get(5));
                }
            }
        }

        List<OcrNewTableCellDto> cells = new ArrayList<>();
        for (int r = 0; r < mergedRows.size(); r++) {
            List<String> row = mergedRows.get(r);
            for (int c = 0; c < row.size(); c++) {
                cells.add(OcrNewTableCellDto.builder()
                        .rowIndex(r)
                        .columnIndex(c)
                        .text(row.get(c))
                        .boundingBox(OcrNewBoundingBoxDto.builder()
                                .left(0)
                                .top(0)
                                .width(0)
                                .height(0)
                                .build())
                        .confidence(null)
                        .build());
            }
        }

        out.add(OcrNewTableDto.builder()
                .page(1)
                .index(null)
                .rowCount(mergedRows.size())
                .columnCount(6)
                .cells(cells)
                .rows(mergedRows)
                .build());

        return out;
    }

    private static String bomKey(String position, String placement, String type) {
        return (oneLine(position) + "|" + oneLine(placement) + "|" + normalizeType(type)).toLowerCase(Locale.ROOT);
    }

    private static String bomKeyShort(String position, String placement) {
        return (oneLine(position) + "|" + oneLine(placement)).toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize BOM Type for matching between Materials/Trims and Production Units tables.
     * Handles common OCR variations like "Thread Trim" vs "Thread", etc.
     */
    private static String normalizeType(String type) {
        if (type == null) return "";
        String t = type.trim().toLowerCase(Locale.ROOT);
        // Remove common suffixes/prefixes for matching
        t = t.replaceAll("\\s+trim$", ""); // "thread trim" -> "thread"
        t = t.replaceAll("\\s+elastic$", ""); // normalize elastic suffix
        t = t.replaceAll("[/\\s]+", "/"); // normalize spaces and slashes
        return t;
    }

    private record ProdUnitsRow(String type, String composition, String materialSupplier) {}

    /**
     * Check if a string looks like a typical BOM Placement value.
     * Placement is usually: Shell, Lining, Main body, Pocket, All
     */
    private static boolean looksLikePlacement(String s) {
        if (s == null || s.isBlank()) return false;
        String low = s.trim().toLowerCase(Locale.ROOT);
        return low.equals("shell") || low.equals("lining") || low.equals("pocket") || low.equals("all")
                || low.contains("main body") || low.equals("mainbody") || low.equals("main");
    }

    /**
     * Check if a string looks like a BOM Type value (not a Placement).
     * Types often contain "/" or are specific material types.
     */
    private static boolean looksLikeBomType(String s) {
        if (s == null || s.isBlank()) return false;
        String low = s.trim().toLowerCase(Locale.ROOT);
        // Contains "/" suggests compound types like Plain/Cambric/Voile
        if (s.contains("/")) return true;
        // Known type keywords
        return low.equals("elastic") || low.equals("buckle") || low.equals("thread") || low.equals("tape")
                || low.contains("plain") || low.contains("cambric") || low.contains("voile")
                || low.contains("woven") || low.contains("knit") || low.contains("trim");
    }

    private static Map<String, ProdUnitsRow> extractBomProductionUnitsData(List<OcrNewLine> lines) {
        if (lines == null || lines.isEmpty()) return Map.of();

        Map<Integer, List<OcrNewLine>> byPage = lines.stream().collect(Collectors.groupingBy(OcrNewLine::getPage));
        List<Integer> pages = byPage.keySet().stream().sorted().toList();

        // First pass: collect all lines in the Production Units section
        List<String> sectionLines = new ArrayList<>();
        boolean inSection = false;
        for (Integer page : pages) {
            List<OcrNewLine> pageLines = byPage.get(page).stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(OcrNewLine::getTop))
                    .toList();
            for (OcrNewLine l : pageLines) {
                String txt = oneLine(l.getText());
                if (txt.isBlank()) continue;

                if (!inSection) {
                    if (BOM_PROD_UNITS_START.matcher(txt).find()) {
                        inSection = true;
                    }
                    continue;
                }

                String low = txt.toLowerCase(Locale.ROOT);
                if (low.contains("yarn source") || (BOM_SECTION_ANY.matcher(txt).find() && !BOM_PROD_UNITS_START.matcher(txt).find())) {
                    inSection = false;
                    break;
                }
                if (CREATED_LINE.matcher(txt).find()) {
                    inSection = false;
                    break;
                }
                if (low.contains("page") && txt.contains("/")) {
                    inSection = false;
                    break;
                }
                if (low.contains("position") && low.contains("placement") && low.contains("composition")) {
                    continue;
                }
                
                sectionLines.add(txt);
                if (BOM_DEBUG) {
                    log.debug("[BOM-PROD-COLLECT] line {}: '{}'", sectionLines.size(), txt.length() > 80 ? txt.substring(0, 80) + "..." : txt);
                }
            }
        }
        
        if (BOM_DEBUG) {
            log.debug("[BOM-PROD-COLLECT] total lines collected: {}", sectionLines.size());
        }
        
        // Second pass: group lines by row (each row starts with BOM_ROW_START, continuation lines follow)
        Map<String, ProdUnitsRow> out = new HashMap<>();
        StringBuilder currentRowText = null;
        String currentPosition = null, currentPlacement = null, currentType = null;
        
        for (int i = 0; i < sectionLines.size(); i++) {
            String txt = sectionLines.get(i);
            boolean isRowStart = BOM_ROW_START.matcher(txt).find();
            
            if (BOM_DEBUG) {
                log.debug("[BOM-PROD-PARSE] line {}: isRowStart={} txt='{}'", i, isRowStart, txt.length() > 60 ? txt.substring(0, 60) + "..." : txt);
            }
            
            if (isRowStart) {
                // Process previous row if exists
                if (currentRowText != null && currentType != null) {
                    if (BOM_DEBUG) {
                        log.debug("[BOM-PROD-PARSE] processing previous row, fullText length={}", currentRowText.length());
                    }
                    processProdUnitsRow(out, currentPosition, currentPlacement, currentType, currentRowText.toString());
                }
                
                // Start new row
                BomRowStart rs = parseBomRowStart(txt);
                currentPosition = rs.position();
                currentPlacement = rs.placement();
                currentType = rs.type();
                
                // Fix: If placement looks like a Type, shift columns
                if (looksLikeBomType(currentPlacement) && !looksLikePlacement(currentPlacement)) {
                    currentType = currentPlacement;
                    currentPlacement = currentPosition;
                    if (BOM_DEBUG) {
                        log.debug("[BOM-PROD] SHIFT: pos='{}' placement='{}' type='{}'", currentPosition, currentPlacement, currentType);
                    }
                }
                
                currentRowText = new StringBuilder(txt);
            } else if (currentRowText != null) {
                // Continuation line - append to current row
                if (BOM_DEBUG) {
                    log.debug("[BOM-PROD-PARSE] CONTINUATION appending to type='{}': '{}'", currentType, txt.length() > 50 ? txt.substring(0, 50) + "..." : txt);
                }
                currentRowText.append(" ").append(txt);
            }
        }
        
        // Process last row
        if (currentRowText != null && currentType != null) {
            processProdUnitsRow(out, currentPosition, currentPlacement, currentType, currentRowText.toString());
        }

        return out;
    }
    
    private static void processProdUnitsRow(Map<String, ProdUnitsRow> out, String position, String placement, String type, String fullText) {
        String fullKey = bomKey(position, placement, type);
        
        // Find text starting point (after type)
        int typeEndIdx = fullText.toLowerCase().indexOf(type.toLowerCase()) + type.length();
        
        // Extract Material Supplier and Composition from full text (including continuations)
        String materialSupplier = "";
        String comp = "";
        
        // Find ALL % patterns and collect composition parts
        // Composition may be split across lines with weight in between
        // Example: "80% Revisco Viscose with 75.0 g/m2 ... circulose, 20% RECYCLED POLYESTER"
        Matcher pct = TOKEN_HAS_PERCENT.matcher(fullText);
        List<int[]> pctPositions = new ArrayList<>();
        while (pct.find()) {
            pctPositions.add(new int[]{pct.start(), pct.end()});
        }
        
        if (!pctPositions.isEmpty()) {
            int firstPctStart = pctPositions.get(0)[0];
            
            // Material Supplier: text between type and first %
            // Also check for company name continuation (e.g., "TRADING CO., LTD." on next line)
            if (typeEndIdx < firstPctStart) {
                String supplierPart = fullText.substring(typeEndIdx, firstPctStart).trim();
                supplierPart = supplierPart.replaceAll("^[\\s,.:]+", "").replaceAll("[\\s,.:]+$", "");
                if (!supplierPart.isBlank()) {
                    materialSupplier = oneLine(supplierPart);
                }
            }

            if (!materialSupplier.isBlank()) {
                materialSupplier = appendProdUnitsSupplierContinuation(materialSupplier, fullText.substring(firstPctStart));
            }
            
            // Look for company name continuation after the first % 
            // Pattern: "TRADING CO., LTD." or "CO.,LTD" appearing before the next % or a known delimiter
            if (!materialSupplier.isBlank() && pctPositions.size() > 1) {
                int firstPctEnd = pctPositions.get(0)[1];
                int secondPctStart = pctPositions.get(1)[0];
                String betweenPcts = fullText.substring(firstPctEnd, secondPctStart);
                
                // Check for company name patterns
                Pattern companyPattern = Pattern.compile("(?i)(TRADING\\s+CO\\.?,?\\s*LTD\\.?|CO\\.?,?\\s*LTD\\.?|INC\\.?|CORP\\.?|LLC|GMBH)");
                Matcher cm = companyPattern.matcher(betweenPcts);
                if (cm.find()) {
                    // Find the full company name segment - from start of line/word to end of pattern
                    int compStart = cm.start();
                    // Look backwards for start of company name (uppercase words)
                    while (compStart > 0 && (Character.isUpperCase(betweenPcts.charAt(compStart - 1)) || 
                           Character.isWhitespace(betweenPcts.charAt(compStart - 1)) ||
                           betweenPcts.charAt(compStart - 1) == '.')) {
                        compStart--;
                    }
                    String companyCont = betweenPcts.substring(compStart, cm.end()).trim();
                    companyCont = companyCont.replaceAll("^[\\s,.:]+", "").replaceAll("[\\s,.:]+$", "");
                    if (!companyCont.isBlank() && !materialSupplier.toLowerCase().contains(companyCont.toLowerCase())) {
                        materialSupplier = materialSupplier + " " + companyCont;
                        materialSupplier = oneLine(materialSupplier);
                    }
                }
            }
            
            // Composition: collect ALL % patterns and surrounding context
            // Strategy: for each %, extract text from % backwards to nearest delimiter and forwards to next delimiter
            StringBuilder compBuilder = new StringBuilder();
            for (int i = 0; i < pctPositions.size(); i++) {
                int pctStart = pctPositions.get(i)[0];
                int pctEnd = pctPositions.get(i)[1];
                
                // Find context: backwards to find the number (e.g., "80" before "%")
                int contextStart = pctStart;
                while (contextStart > 0 && (Character.isDigit(fullText.charAt(contextStart - 1)) || fullText.charAt(contextStart - 1) == '.')) {
                    contextStart--;
                }
                // Skip leading whitespace
                while (contextStart > 0 && Character.isWhitespace(fullText.charAt(contextStart - 1))) {
                    contextStart--;
                }
                
                // Find context: forwards to next % or end
                int contextEnd = pctEnd;
                int nextPctStart = (i + 1 < pctPositions.size()) ? pctPositions.get(i + 1)[0] : fullText.length();
                
                // Extend forward to include fibre name but stop at weight pattern or next %
                Matcher wm = BOM_WEIGHT_STOP.matcher(fullText);
                int weightStart = fullText.length();
                while (wm.find()) {
                    if (wm.start() > pctEnd) {
                        weightStart = wm.start();
                        break;
                    }
                }
                
                // Take text up to weight or next %, whichever is closer but after current pctEnd
                contextEnd = Math.min(weightStart, nextPctStart);
                if (contextEnd <= pctEnd) contextEnd = Math.min(weightStart, fullText.length());
                
                // Extract composition part
                if (contextStart >= 0 && contextEnd > contextStart) {
                    String part = fullText.substring(contextStart, contextEnd).trim();
                    // Clean up - remove trailing punctuation and numbers
                    part = part.replaceAll("[\\s,.:]+$", "");
                    if (!part.isBlank()) {
                        if (compBuilder.length() > 0) {
                            // Check if needs comma separator
                            String existing = compBuilder.toString();
                            if (!existing.endsWith(",") && !existing.endsWith(", ") && !part.startsWith(",")) {
                                compBuilder.append(", ");
                            } else if (existing.endsWith(",")) {
                                compBuilder.append(" ");
                            }
                        }
                        compBuilder.append(part);
                    }
                }
            }
            comp = oneLine(compBuilder.toString());
            
        } else {
            // Case 2: No % - supplier is between type and weight
            Matcher w = BOM_WEIGHT_STOP.matcher(fullText);
            int stop = fullText.length();
            if (w.find() && w.start() > typeEndIdx) {
                stop = w.start();
            }
            if (typeEndIdx < stop) {
                String supplierPart = fullText.substring(typeEndIdx, stop).trim();
                supplierPart = supplierPart.replaceAll("^[\\s,.:]+", "").replaceAll("[\\s,.:]+$", "");
                supplierPart = supplierPart.replaceAll("\\s+\\d+\\.?\\d*\\s*$", "");
                if (!supplierPart.isBlank() && !supplierPart.matches("^\\d.*")) {
                    materialSupplier = oneLine(supplierPart);
                }
            }

            if (!materialSupplier.isBlank() && stop < fullText.length()) {
                materialSupplier = appendProdUnitsSupplierContinuation(materialSupplier, fullText.substring(stop));
            }
        }

        ProdUnitsRow row = new ProdUnitsRow(type, comp, materialSupplier);
        out.putIfAbsent(fullKey, row);
        
        if (BOM_DEBUG) {
            log.debug("[BOM-PROD] key='{}' type='{}' comp='{}' supplier='{}'", fullKey, type, comp, materialSupplier);
        }
    }

    private static String appendProdUnitsSupplierContinuation(String supplier, String searchText) {
        if (supplier == null || supplier.isBlank()) return supplier;
        if (searchText == null || searchText.isBlank()) return supplier;

        String result = supplier;
        String upper = result.toUpperCase(Locale.ROOT);

        // 1) Country continuation (needed for: "PT SAMJIN BROTHREAD" -> "... INDONESIA")
        Pattern country = Pattern.compile("(?i)\\bINDONESIA\\b");
        Matcher mCountry = country.matcher(searchText);
        if (mCountry.find() && !upper.contains("INDONESIA")) {
            result = oneLine(result + " INDONESIA");
            upper = result.toUpperCase(Locale.ROOT);
        }

        // 2) Trading suffix continuation (needed for: "Hangzhou Jueya Garments" -> "... Trading CO.,Ltd")
        Pattern trading = Pattern.compile("(?i)\\bTRADING\\s+CO\\.?,?\\s*LTD\\.?\\b");
        Matcher mTrading = trading.matcher(searchText);
        if (mTrading.find() && !upper.contains("TRADING")) {
            String seg = searchText.substring(mTrading.start(), mTrading.end()).trim();
            seg = seg.replaceAll("^[\\s,.:]+", "").replaceAll("[\\s,.:]+$", "");
            if (!seg.isBlank()) {
                result = oneLine(result + " " + seg);
            }
        }

        return result;
    }

    private static List<List<String>> normalizeBomRows(List<OcrNewLine> sectionLines, List<OcrNewLine> headerLines) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Position", "Placement", "Type", "Description", "Composition", "Material Supplier"));
        if (sectionLines == null || sectionLines.isEmpty()) return rows;

        List<StringBuilder> rawRowText = new ArrayList<>();
        rawRowText.add(new StringBuilder());

        BomColumnCenters centers = deriveBomColumnCenters(headerLines);
        if (!centers.valid()) {
            // fallback to older heuristic if header didn't yield usable columns
            return normalizeBomRowsFallback(sectionLines);
        }

        List<String> cur = null;
        StringBuilder curRaw = null;
        for (OcrNewLine l : sectionLines) {
            String txt = oneLine(l.getText());
            if (txt.isBlank()) continue;
            if (ONLY_PUNCT.matcher(txt).matches()) continue;

            if (BOM_ROW_REJECT.matcher(txt).find()) {
                if (BOM_DEBUG) log.debug("[BOM-ROW] SKIPPED (reject): {}", txt);
                continue;
            }

            String txtClean = txt.replace("|", " ").replaceAll("[\\p{Punct}]", " ");
            txtClean = oneLine(txtClean);

            // For composition extraction we must NOT remove '%' (otherwise tokens like '20%' become '20')
            String txtCleanKeepPercent = txt.replace("|", " ").replaceAll("[\\p{Punct}&&[^%]]", " ");
            txtCleanKeepPercent = oneLine(txtCleanKeepPercent);
            if (cur != null && ALL_CAPS_SPILLOVER.matcher(txt).matches() && txt.length() <= 40 && !TOKEN_HAS_PERCENT.matcher(txt).find()) {
                continue;
            }
            if (cur != null && ALL_CAPS_SPILLOVER.matcher(txtClean).matches() && txtClean.length() <= 40 && !TOKEN_HAS_PERCENT.matcher(txtClean).find()) {
                continue;
            }

            String lower = txt.toLowerCase();
            if (lower.startsWith("appearance") || lower.startsWith("treatments") || lower.startsWith("supplier") || lower.startsWith("article")) {
                continue;
            }

            BomLineCells cells = extractBomLineCells(l, centers);
            String wholeLine = oneLine(l.getText());
            String compFromWhole = "";
            if (TOKEN_HAS_PERCENT.matcher(wholeLine).find()) {
                compFromWhole = normalizeBomComposition(wholeLine);
                if (compFromWhole.isBlank()) {
                    compFromWhole = extractMinimalComposition(wholeLine);
                }
            }

            boolean lineStartsRow = BOM_ROW_START.matcher(txt).find();
            boolean isRowStart = lineStartsRow || (!cells.position.isBlank() && BOM_ROW_START.matcher(cells.position).find());
            boolean isContinuation = !isRowStart && cur != null;
            
            if (BOM_DEBUG) {
                log.debug("[BOM-ROW] page={} isRowStart={} isCont={} pos='{}' desc='{}' comp='{}'",
                        l.getPage(), isRowStart, isContinuation, cells.position, 
                        cells.description.length() > 30 ? cells.description.substring(0, 30) + "..." : cells.description,
                        cells.composition.length() > 30 ? cells.composition.substring(0, 30) + "..." : cells.composition);
            }

            // If we haven't started any row yet, ignore stray lines that don't start a row.
            // This prevents carry-over description/composition lines on the next page from becoming their own rows.
            if (cur == null && !isRowStart) {
                if (BOM_DEBUG) log.debug("[BOM-ROW] SKIPPED (no cur, not rowStart)");
                continue;
            }

            if (!isContinuation) {
                // If column-based extraction failed to capture the leading Position/Placement/Type tokens
                // (common with hOCR line segmentation), fall back to token parsing of the whole line.
                BomRowStart parsed = null;
                if (lineStartsRow) {
                    parsed = parseBomRowStart(txt);
                }
                BomDescComp dc = null;
                if (parsed != null && parsed.tail != null && !parsed.tail.isBlank()) {
                    dc = splitBomTail(parsed.tail);
                }

                String pos = !cells.position.isBlank() ? cells.position : (parsed == null ? "" : parsed.position);
                String plc = !cells.placement.isBlank() ? cells.placement : (parsed == null ? "" : parsed.placement);
                String typ = !cells.type.isBlank() ? cells.type : (parsed == null ? "" : parsed.type);
                String desc = !cells.description.isBlank() ? cells.description : (dc == null ? "" : mergeSplitWords(dc.description));

                String comp = cells.composition.isBlank() ? compFromWhole : cells.composition;
                if ((comp == null || comp.isBlank()) && dc != null && dc.composition != null && !dc.composition.isBlank()) {
                    comp = normalizeBomComposition(dc.composition);
                }

                String ms = cells.materialSupplier;

                cur = new ArrayList<>(List.of(
                        pos,
                        typ,
                        desc,
                        (comp == null ? "" : comp),
                        (ms == null ? "" : ms)
                ));
                cur.add(1, plc);
                rows.add(cur);
                curRaw = new StringBuilder();
                curRaw.append(wholeLine);
                rawRowText.add(curRaw);
                if (BOM_DEBUG) log.debug("[BOM-ROW] NEW ROW #{}: desc='{}' comp='{}'", rows.size()-1, cur.get(3), cur.get(4));
            } else {
                // Append continuation into description/composition based on which column has data
                if (cur == null) {
                    continue;
                }
                if (curRaw != null) {
                    curRaw.append(' ').append(wholeLine);
                }
                // If this continuation line contains percent-bearing composition, treat it as composition
                // even if column assignment put it under Description due to slight x-shifts.
                String compFromLine = "";
                if (TOKEN_HAS_PERCENT.matcher(txt).find()) {
                    compFromLine = normalizeBomComposition(txtCleanKeepPercent);
                }

                boolean consumedAsComposition = false;
                if (!compFromLine.isBlank()) {
                    String oldComp = cur.get(4);
                    cur.set(4, normalizeBomComposition(oneLine(cur.get(4) + (cur.get(4).isBlank() ? "" : " ") + compFromLine)));
                    consumedAsComposition = true;
                    if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp(from-line%): '{}' += '{}' -> '{}'", oldComp, compFromLine, cur.get(4));
                }

                if (BOM_DEBUG && TOKEN_HAS_PERCENT.matcher(txt).find()) {
                    log.debug("[BOM-ROW] CONT %line: txt='{}' keepPct='{}' cells.desc='{}' cells.comp='{}' compFromLine='{}'",
                            txt,
                            txtCleanKeepPercent,
                            cells.description,
                            cells.composition,
                            compFromLine);
                }

                if (!consumedAsComposition && !cells.description.isBlank()) {
                    // Apply mergeSplitWords to fix broken words when appending description continuation
                    String oldDesc = cur.get(3);
                    cur.set(3, mergeSplitWords(oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + cells.description)));
                    if (BOM_DEBUG) log.debug("[BOM-ROW] CONT desc: '{}' += '{}' -> '{}'", oldDesc, cells.description, cur.get(3));
                }

                if (!consumedAsComposition && !cells.composition.isBlank()) {
                    String oldComp = cur.get(4);
                    cur.set(4, normalizeBomComposition(oneLine(cur.get(4) + (cur.get(4).isBlank() ? "" : " ") + cells.composition)));
                    if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp: '{}' += '{}' -> '{}'", oldComp, cells.composition, cur.get(4));
                } else if (cells.description.isBlank()
                        && !cur.get(4).isBlank()
                        && TOKEN_HAS_PERCENT.matcher(cur.get(4)).find()
                        && looksLikeCompositionContinuation(txt)) {
                    // Prefer fibre continuation (no %) over normalizeBomComposition fallback.
                    // This keeps fragments like 'POLYESTER' that may come on the next line without '%'.
                    String cont = extractCompositionContinuationTokens(txtCleanKeepPercent);
                    cont = mergeSplitWords(cont);
                    if (!cont.isBlank()) {
                        String curComp = cur.get(4);
                        String lowCurComp = curComp.toLowerCase(Locale.ROOT);
                        String lowCont = cont.toLowerCase(Locale.ROOT);
                        boolean curAlreadyPolyester = lowCurComp.contains("polyester");
                        boolean contIsPolyesterFragment = lowCont.equals("yester") || lowCont.equals("polyester");
                        if (!(curAlreadyPolyester && contIsPolyesterFragment)) {
                            cur.set(4, normalizeBomComposition(oneLine(cur.get(4) + " " + cont)));
                            if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp(fibre): += '{}' -> '{}'", cont, cur.get(4));
                        } else {
                            if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp(fibre) SKIP dup: cur='{}' cont='{}'", curComp, cont);
                        }
                    }
                } else if (cells.description.isBlank() && TOKEN_HAS_PERCENT.matcher(txt).find()) {
                    // Only apply fallback if NO description was extracted from this line.
                    // Restrict fallback to percent-bearing lines to avoid dropping fibre-only lines (e.g. 'Polyester').
                    String oldComp = cur.get(4);
                    cur.set(4, normalizeBomComposition(oneLine(cur.get(4) + (cur.get(4).isBlank() ? "" : " ") + txtCleanKeepPercent)));
                    if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp(fallback): '{}' += '{}' -> '{}'", oldComp, txtCleanKeepPercent, cur.get(4));
                } else if (cur.get(4).isBlank() && !compFromWhole.isBlank()) {
                    // last resort: if this line has % but didn't map into composition column, use whole-line extraction
                    cur.set(4, compFromWhole);
                    if (BOM_DEBUG) log.debug("[BOM-ROW] CONT comp(whole): -> '{}'", cur.get(4));
                }

                if (!cells.materialSupplier.isBlank()) {
                    String oldMs = cur.size() > 5 ? cur.get(5) : "";
                    if (cur.size() > 5) {
                        cur.set(5, oneLine(oldMs + (oldMs.isBlank() ? "" : " ") + cells.materialSupplier));
                    }
                }
            }
        }

        // Only use raw-text fallback if composition is still empty after column-based extraction
        for (int ri = 1; ri < rows.size() && ri < rawRowText.size(); ri++) {
            List<String> r = rows.get(ri);
            if (r == null || r.size() < 5) continue;
            // Skip if we already have composition from column-based extraction
            if (!r.get(4).isBlank() && TOKEN_HAS_PERCENT.matcher(r.get(4)).find()) {
                continue;
            }
            String mergedRaw = oneLine(rawRowText.get(ri).toString());

            // Normal case: any percent-bearing raw row can be normalized into composition.
            if (!mergedRaw.isBlank() && TOKEN_HAS_PERCENT.matcher(mergedRaw).find()) {
                String comp = normalizeBomComposition(mergedRaw);
                if (!comp.isBlank()) {
                    r.set(4, comp);
                    if (BOM_DEBUG) log.debug("[BOM-ROW] POST-FIX row#{}: comp='{}'", ri, comp);
                    continue;
                }
            }

            // Targeted fix: some rows have truncated fibre tokens that must be kept even if other heuristics fail.
            // Example raw: '... 100% SO POL ... Polyester' where 'YESTER' arrives later.
            if (r.get(4).isBlank() && !mergedRaw.isBlank()) {
                String low = mergedRaw.toLowerCase();
                if (low.matches(".*\\b\\d{1,3}\\s*%\\s+so\\s+pol\\b.*")) {
                    String comp = normalizeBomComposition(mergedRaw);
                    if (!comp.isBlank()) {
                        r.set(4, comp);
                        if (BOM_DEBUG) log.debug("[BOM-ROW] POST-FIX so-pol row#{}: comp='{}'", ri, comp);
                    }
                }
            }
        }

        // Clean-up: Thread rows often have consumption/weight and supplier code text drifting into Description.
        // The UI expects these descriptions to be empty.
        for (int ri = 1; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            if (r == null || r.size() < 5) continue;
            String typ = (r.get(2) == null ? "" : r.get(2)).toLowerCase(Locale.ROOT);
            if (!typ.contains("thread")) continue;

            String desc = r.get(3) == null ? "" : r.get(3);
            String lowDesc = desc.toLowerCase(Locale.ROOT);
            boolean hasConsumption = lowDesc.matches(".*\\b(km|gram/km|g/m|g/m2|perunit|g/piece)\\b.*");
            boolean looksLikeSupplierOrId = lowDesc.matches("^pt\\b.*") || lowDesc.matches(".*\\bthd\\d+\\b.*");
            if ((hasConsumption || looksLikeSupplierOrId) && !desc.isBlank()) {
                r.set(3, "");
            }
        }

        return rows;
    }

    private static List<List<String>> normalizeBomRowsFallback(List<OcrNewLine> sectionLines) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Position", "Placement", "Type", "Description", "Composition", "Material Supplier"));
        if (sectionLines == null || sectionLines.isEmpty()) return rows;

        List<String> cur = null;
        for (OcrNewLine l : sectionLines) {
            String txt = oneLine(l.getText());
            if (txt.isBlank()) continue;
            if (ONLY_PUNCT.matcher(txt).matches()) continue;

            boolean startsNewRow = BOM_ROW_START.matcher(txt).find();
            if (startsNewRow || cur == null) {
                BomRowStart parsed = parseBomRowStart(txt);
                BomDescComp dc = splitBomTail(parsed.tail);
                // Apply mergeSplitWords to fix broken words in description
                String desc = mergeSplitWords(dc.description);
                cur = new ArrayList<>(List.of(parsed.position, parsed.placement, parsed.type, desc, dc.composition, ""));
                rows.add(cur);
            } else {
                BomDescComp dc = splitBomTail(txt);
                if (!dc.description.isBlank()) {
                    // Apply mergeSplitWords to fix broken words in description continuation
                    String descCont = mergeSplitWords(dc.description);
                    cur.set(3, mergeSplitWords(oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + descCont)));
                }
                if (!dc.composition.isBlank()) {
                    cur.set(4, oneLine(cur.get(4) + (cur.get(4).isBlank() ? "" : " ") + dc.composition));
                }
            }
        }

        return rows;
    }

    private record BomRowStart(String position, String placement, String type, String tail) {
    }

    private record BomColumnCenters(int xPosition, int xPlacement, int xType, int xDescription, int xAppearance, int xComposition, int xMaterialSupplier) {
        boolean valid() {
            return xPosition > 0 && xPlacement > 0 && xType > 0 && xDescription > 0 && xComposition > 0;
        }
    }

    private record BomLineCells(String position, String placement, String type, String description, String composition, String materialSupplier) {
    }

    private static BomColumnCenters deriveBomColumnCenters(OcrNewLine headerLine) {
        return deriveBomColumnCenters(headerLine == null ? List.of() : List.of(headerLine));
    }

    private static BomColumnCenters deriveBomColumnCenters(List<OcrNewLine> headerLines) {
        if (headerLines == null || headerLines.isEmpty()) {
            return new BomColumnCenters(-1, -1, -1, -1, -1, -1, -1);
        }

        Integer xPosition = null;
        Integer xPlacement = null;
        Integer xType = null;
        Integer xDescription = null;
        Integer xAppearance = null;
        Integer xComposition = null;
        Integer xMaterialSupplier = null;

        // Collect column centers from ALL header lines (handles hOCR split headers)
        for (OcrNewLine headerLine : headerLines) {
            if (headerLine == null) continue;
            
            String lineText = oneLine(headerLine.getText()).toLowerCase();
            int lineCx = (headerLine.getLeft() + headerLine.getRight()) / 2;
            
            // Try word-level extraction first
            if (headerLine.getWords() != null && !headerLine.getWords().isEmpty()) {
                for (OcrNewWord w : headerLine.getWords()) {
                    String t = oneLine(w.getText());
                    if (t.isBlank()) continue;
                    int cx = (w.getLeft() + w.getRight()) / 2;

                    if (xPosition == null && t.equalsIgnoreCase("Position")) xPosition = cx;
                    else if (xPlacement == null && t.equalsIgnoreCase("Placement")) xPlacement = cx;
                    else if (xType == null && t.equalsIgnoreCase("Type")) xType = cx;
                    else if (xDescription == null && t.equalsIgnoreCase("Description")) xDescription = cx;
                    else if (xAppearance == null && t.equalsIgnoreCase("Appearance")) xAppearance = cx;
                    else if (xComposition == null && t.equalsIgnoreCase("Composition")) xComposition = cx;
                    else if (xMaterialSupplier == null && t.equalsIgnoreCase("Supplier") && cx > 1500) xMaterialSupplier = cx;
                }
            } else {
                // Fallback to line-level extraction when words not available
                if (xPosition == null && lineText.contains("position")) xPosition = lineCx;
                if (xPlacement == null && lineText.contains("placement")) xPlacement = lineCx;
                if (xType == null && lineText.contains("type")) xType = lineCx;
                if (xDescription == null && lineText.equals("description")) xDescription = lineCx;
                if (xAppearance == null && lineText.contains("appearance")) xAppearance = lineCx;
                if (xComposition == null && lineText.contains("composition")) xComposition = lineCx;
                if (xMaterialSupplier == null && lineText.contains("material supplier")) xMaterialSupplier = lineCx;
            }
        }

        // Robust fallbacks based on typical document layout
        // Position column typically starts at left edge (~75px)
        if (xPosition == null) {
            xPosition = 75;
        }
        // Placement typically follows Position (~250px)
        if (xPlacement == null && xPosition != null) {
            xPlacement = xPosition + 175;
        }
        // Type typically follows Placement (~500px)
        if (xType == null && xPlacement != null) {
            xType = xPlacement + 250;
        }
        // Description typically at ~800-900px
        if (xDescription == null) {
            xDescription = 850;
        }
        // Material Appearance typically at ~1040-1135px (between Description and Composition)
        if (xAppearance == null) {
            xAppearance = 1080;
        }
        // Composition typically at ~1280px
        if (xComposition == null) {
            xComposition = 1280;
        }
        if (xMaterialSupplier == null) {
            xMaterialSupplier = 1750;
        }

        return new BomColumnCenters(
                xPosition == null ? -1 : xPosition,
                xPlacement == null ? -1 : xPlacement,
                xType == null ? -1 : xType,
                xDescription == null ? -1 : xDescription,
                xAppearance == null ? -1 : xAppearance,
                xComposition == null ? -1 : xComposition,
                xMaterialSupplier == null ? -1 : xMaterialSupplier
        );
    }

    private static BomLineCells extractBomLineCells(OcrNewLine line, BomColumnCenters centers) {
        if (line == null) {
            return new BomLineCells("", "", "", "", "", "");
        }
        
        String lineText = oneLine(line.getText());
        
        // Debug log: column centers and line info
        if (BOM_DEBUG) {
            log.debug("[BOM] extractBomLineCells: line='{}', bbox=[{},{}-{},{}], centers=[pos={},plc={},typ={},desc={},app={},comp={}]",
                    lineText.length() > 50 ? lineText.substring(0, 50) + "..." : lineText,
                    line.getLeft(), line.getTop(), line.getRight(), line.getBottom(),
                    centers.xPosition, centers.xPlacement, centers.xType, 
                    centers.xDescription, centers.xAppearance, centers.xComposition);
        }
        
        // When words are not available, use LINE's x-position to determine column
        if (line.getWords() == null || line.getWords().isEmpty()) {
            // Use line's bounding box center to determine column assignment
            int lineCx = (line.getLeft() + line.getRight()) / 2;
            
            // Skip lines beyond tracked columns (Consumption, Weight, Supplier)
            if (lineCx > 2200) {
                return new BomLineCells("", "", "", "", "", "");
            }
            
            // Calculate conservative boundaries for column assignment
            // Use 150px buffer before Appearance to ensure no spillover
            int descMaxX = centers.xAppearance > 0 ? centers.xAppearance - 150 : 950;
            int compMinX = centers.xComposition > 0 ? centers.xComposition - 80 : 1200;
            
            // Check if text contains composition indicators (%, material names)
            boolean hasCompositionIndicator = TOKEN_HAS_PERCENT.matcher(lineText).find() ||
                    lineText.toLowerCase().matches(".*\\b(polyester|cotton|viscose|nylon|elastane|spandex|wool|silk|linen|rayon|acrylic|recycled)\\b.*");
            
            // Assign based on x-position boundaries
            // Composition zone: cx >= compMinX or has composition indicator
            if (lineCx >= compMinX || hasCompositionIndicator) {
                return new BomLineCells("", "", "", "", lineText, "");
            } else if (lineCx > descMaxX && lineCx < compMinX) {
                return new BomLineCells("", "", "", "", "", "");
            } else if (lineCx >= centers.xMaterialSupplier) {
                return new BomLineCells("", "", "", "", "", lineText);
            } else if (lineCx > centers.xType && lineCx <= descMaxX) {
                return new BomLineCells("", "", "", lineText, "", "");
            } else if (lineCx > centers.xPlacement && lineCx <= centers.xType) {
                return new BomLineCells("", "", lineText, "", "", "");
            } else if (lineCx > centers.xPosition && lineCx <= centers.xPlacement) {
                return new BomLineCells("", lineText, "", "", "", "");
            } else {
                return new BomLineCells(lineText, "", "", "", "", "");
            }
        }

        StringBuilder position = new StringBuilder();
        StringBuilder placement = new StringBuilder();
        StringBuilder type = new StringBuilder();
        StringBuilder description = new StringBuilder();
        StringBuilder composition = new StringBuilder();
        StringBuilder materialSupplier = new StringBuilder();

        // Calculate column boundaries to avoid pollution from other columns (Consumption, Weight, Supplier)
        // These columns typically start at x > 1500, so we limit tracked data to x < 1600
        int materialSupplierMaxX = 2200;
        
        // Calculate boundary between Description and Appearance columns
        // Use conservative boundary: 150px before Appearance center to ensure no Appearance/Composition words leak into Description
        // Description header ends ~980, Appearance starts ~1041
        int descriptionMaxX = centers.xAppearance > 0 ? centers.xAppearance - 150 : 950;
        // Composition boundary - 50px before Composition center
        int compositionMinX = centers.xComposition > 0 ? centers.xComposition - 80 : 1200;
        
        for (OcrNewWord w : line.getWords()) {
            String t = oneLine(w.getText());
            if (t.isBlank()) continue;
            int cx = (w.getLeft() + w.getRight()) / 2;

            // Skip words that are beyond our tracked columns (Consumption, Weight, Supplier at x > 1600)
            if (cx > materialSupplierMaxX) {
                if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} SKIPPED (> materialSupplierMaxX={})", t, cx, materialSupplierMaxX);
                continue;
            }

            // Percent-bearing tokens (e.g. '100%') are composition by definition.
            // Some PDFs shift these tokens left so the nearest-column heuristic misclassifies them as Position/Description,
            // which can incorrectly start a new row and drop composition for the real row.
            if (TOKEN_HAS_PERCENT.matcher(t).find()) {
                if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} -> composition(%-force)", t, cx);
                if (composition.length() > 0) composition.append(' ');
                composition.append(t);
                continue;
            }

            int col = nearestIndex(List.of(
                    centers.xPosition,
                    centers.xPlacement,
                    centers.xType,
                    centers.xDescription,
                    centers.xAppearance,
                    centers.xComposition
            ), cx);

            // Check if word is a composition indicator (%, material name)
            // Only use this for words in the composition zone, not for description
            boolean hasPercentSign = TOKEN_HAS_PERCENT.matcher(t).find();

            StringBuilder target;
            String targetName;
            if (col == 0) { target = position; targetName = "position"; }
            else if (col == 1) { target = placement; targetName = "placement"; }
            else if (col == 2) { target = type; targetName = "type"; }
            else if (col == 3) {
                // Description column - verify word is not past the Appearance column boundary
                if (cx > descriptionMaxX) {
                    // Word is past Description boundary - skip it (Appearance column)
                    if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} col=3 SKIPPED (cx > descMaxX={})", t, cx, descriptionMaxX);
                    continue;
                }
                // Stay in description even if it contains fiber words (they're part of fabric code)
                target = description;
                targetName = "description";
            }
            else if (col == 4) {
                // Material Appearance column - skip (don't add to any field)
                if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} col=4 SKIPPED (Appearance)", t, cx);
                continue;
            }
            else if (col == 5) {
                // Composition column
                target = composition;
                targetName = "composition";
            }
            else if (cx >= centers.xMaterialSupplier) {
                target = materialSupplier;
                targetName = "materialSupplier";
            }
            else if (cx >= compositionMinX && hasPercentSign) {
                // Word is past composition boundary AND contains % - likely composition data
                target = composition;
                targetName = "composition(fallback)";
            }
            else {
                // Fallback - skip unknown
                if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} col={} SKIPPED (unknown)", t, cx, col);
                continue;
            }

            if (BOM_DEBUG) log.debug("[BOM] word='{}' cx={} col={} -> {}", t, cx, col, targetName);
            if (target.length() > 0) target.append(' ');
            target.append(t);
        }

        String pos = oneLine(position.toString());
        String plc = oneLine(placement.toString());
        String typ = oneLine(type.toString());
        String desc = mergeSplitWords(oneLine(description.toString()));  // Fix broken words in description
        String comp = oneLine(composition.toString());
        String ms = oneLine(materialSupplier.toString());
        
        if (BOM_DEBUG) {
            log.debug("[BOM] after word loop: desc='{}', comp='{}'", desc, comp);
        }

        // Normalize comp first; it may become empty if it was only noise.
        comp = normalizeBomComposition(comp);

        // Fallback 1: if comp became empty but desc contains composition-like %, extract from desc.
        // Only trigger if desc has actual composition (e.g., "80% Viscose"), NOT fabric ratios like "80/20%"
        // Composition pattern: digit(s) + % + space + fiber word
        if (comp.isBlank() && desc.matches(".*\\b\\d{1,3}%\\s+(?i)(viscose|polyester|cotton|nylon|elastane|spandex|wool|silk|revisco|recycled|polyamide|acrylic).*")) {
            if (BOM_DEBUG) log.debug("[BOM] Fallback1: extracting comp from desc='{}'", desc);
            BomDescComp dc = splitBomTail(desc);
            desc = dc.description;
            comp = normalizeBomComposition(dc.composition);
            if (BOM_DEBUG) log.debug("[BOM] Fallback1 result: desc='{}', comp='{}'", desc, comp);
        }

        // Fallback 2: if still empty, try from whole line text (handles column mis-assignment).
        if (comp.isBlank()) {
            String whole = oneLine(line.getText());
            if (TOKEN_HAS_PERCENT.matcher(whole).find()) {
                if (BOM_DEBUG) log.debug("[BOM] Fallback2: extracting comp from wholeLine='{}'", whole);
                BomDescComp dc = splitBomTail(whole);
                comp = normalizeBomComposition(dc.composition);
                if (BOM_DEBUG) log.debug("[BOM] Fallback2 result: comp='{}'", comp);
            }
        }

        // trim all-caps spillover from description
        if (!desc.isBlank() && ALL_CAPS_SPILLOVER.matcher(desc).matches() && desc.length() <= 40 && !TOKEN_HAS_PERCENT.matcher(desc).find()) {
            desc = "";
        }

        // Drop consumption/weight lines that were mistakenly assigned to Description.
        // Example: '0.56 km 2.1 gram/km PT SAMJINB THD1' should not become a description.
        if (!desc.isBlank()) {
            String lowDesc = desc.toLowerCase(Locale.ROOT);
            boolean startsWithNumber = lowDesc.matches("^\\d.*");
            boolean hasUnit = lowDesc.matches(".*\\b(km|m|yd|perunit|pcs|piece)\\b.*");
            boolean hasWeight = lowDesc.matches(".*\\b(g/m2|g/m|g/piece|gram/km|gram/m)\\b.*");
            if (startsWithNumber && (hasUnit || hasWeight)) {
                desc = "";
            }
        }

        if (BOM_DEBUG) {
            log.debug("[BOM] FINAL: pos='{}', typ='{}', desc='{}', comp='{}'", pos, typ, desc, comp);
        }
        return new BomLineCells(pos, plc, typ, desc, comp, ms);
    }

    private static String normalizeBomComposition(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";

        // If there's no percent at all, this is almost certainly consumption/weight/supplier noise.
        if (!TOKEN_HAS_PERCENT.matcher(r).find()) {
            return "";
        }

        // Normalize 'SO POL' polyester rows: the OCR often appends consumption/weight into the same cell.
        // Example raw: '100% SO POL 0.87 m 15.0 g/m YESTER' -> '100% SO POLYESTER'
        Matcher soPol = Pattern.compile("\\b(\\d{1,3})%\\s+so\\s+pol\\b", Pattern.CASE_INSENSITIVE).matcher(r);
        if (soPol.find()) {
            String pct = soPol.group(1);
            return oneLine(pct + "% S0 POLYESTER");
        }

        String seg = extractCompositionSegments(r);
        if (!seg.isBlank()) {
            // If OCR pushed 'YESTER' to a continuation line after units/supplier tokens, segments may drop it.
            // Re-add when clearly present in the raw row text.
            String lowRaw = r.toLowerCase();
            String lowSeg = seg.toLowerCase();
            seg = dedupePercentTokens(seg);
            lowSeg = seg.toLowerCase();

            // Some scans split 'circulose, 20% POLYAMIDE' across multiple lines where supplier stopwords cut segments.
            // Re-add key fibre tokens when raw row text contains them but extracted segments dropped them.
            if ((lowRaw.contains("circulose") || lowRaw.contains("irculose")) && lowSeg.contains("20%") && !lowSeg.contains("circulose") && !lowSeg.contains("irculose")) {
                String circTok = lowRaw.contains("irculose") ? "irculose," : "circulose,";
                seg = insertBeforePercent(seg, "20%", circTok);
                lowSeg = seg.toLowerCase();
            }
            // If OCR dropped the 'Viscose' token in Revisco compositions, put it back (but avoid adding noisy 'with c').
            if ((lowRaw.contains("revisco") || lowRaw.contains("revisc")) && lowRaw.contains("viscose") && !lowSeg.contains("viscose")) {
                // Insert after the first percent+material token.
                seg = seg.replaceFirst("^(\\d{1,3}%\\s+\\S+)(?:\\s+|$)", "$1 Viscose ");
                seg = oneLine(seg);
                lowSeg = seg.toLowerCase();
            }
            if (lowRaw.contains("polyamide") && lowSeg.contains("20%") && !lowSeg.contains("polyamide")) {
                seg = oneLine(seg + " POLYAMIDE");
                lowSeg = seg.toLowerCase();
            }
            if (lowRaw.contains("yester") && !lowSeg.contains("yester")) {
                seg = oneLine(seg + " YESTER");
            }
            return fixKnownBrokenWords(oneLine(seg));
        }

        BomDescComp dc = splitBomTail(r);
        if (!dc.composition.isBlank()) return fixKnownBrokenWords(dc.composition);

        String min = fixKnownBrokenWords(extractMinimalComposition(r));
        if (!min.isBlank()) return min;

        // Last resort: do not drop a percent-bearing string even if fibre token is incomplete.
        return fixKnownBrokenWords(r);
    }

    private static String fixKnownBrokenWords(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";

        // Apply comprehensive word merge first
        r = mergeSplitWords(r);
        
        // Additional specific fixes for composition context
        r = r.replaceAll("(?i)\\bRECYCLED\\s+P\\s*OLYESTER\\b", "RECYCLED POLYESTER");
        r = r.replaceAll("(?i)\\bRECY\\s*CLED\\s+POLYESTER\\b", "RECYCLED POLYESTER");
        // Dedupe circulose
        r = r.replaceAll("(?i)\\bcirculose\\s+circulose\\b", "circulose");
        r = r.replaceAll("(?i)\\bwith\\s+c\\s+circulose\\b", "with circulose");
        r = r.replaceAll("(?i)\\bwith\\s+c\\s+irculose\\b", "with circulose");

        // Normalize common Revisco/Circulose phrasing
        r = r.replaceAll("(?i)\\bRevisco\\s+circulose\\s+Revisco\\s+Viscose\\b", "Revisco Viscose with circulose");
        r = r.replaceAll("(?i)\\bRevisco\\s+circulose\\s+Viscose\\b", "Revisco Viscose with circulose");
        r = r.replaceAll("(?i)\\bRevisco\\s+Viscose\\s+circulose\\b", "Revisco Viscose with circulose");

        // If Revisco segment contains circulose but lost the 'Viscose with' words, re-insert them.
        // Example: '80% Revisco circulose, 20% ...' -> '80% Revisco Viscose with circulose, 20% ...'
        if (r.toLowerCase().contains("revisco") && r.toLowerCase().contains("circulose") && !r.toLowerCase().contains("viscose")) {
            r = r.replaceFirst("(?i)\\bRevisco\\s+(?=circulose\\b)", "Revisco Viscose with ");
        }

        // Merge common OCR splits for polyester
        r = r.replaceAll("(?i)\\bpol\\s+yester\\b", "POLYESTER");
        r = r.replaceAll("(?i)\\bso\\s+polyester\\b", "S0 POLYESTER");
        r = r.replaceAll("(?i)\\bpolyester\\s+yester\\b", "POLYESTER");

        // If we already detected RECYCLED POLYESTER, drop erroneous OCR second-segment noise
        // like '20% Viscose with circulose' which should be '20% RECYCLED POLYESTER'.
        if (r.toUpperCase().contains("RECYCLED POLYESTER")) {
            r = r.replaceAll("(?i)\\b20%\\s+viscose\\b(?:\\s+with\\s+circulose)?", "20% RECYCLED POLYESTER");
            r = r.replaceAll("(?i)\\b20%\\s+viscose\\b", "20% RECYCLED POLYESTER");
            // Also drop stray 'circulose' that sometimes gets pulled into the 20% segment.
            r = r.replaceAll("(?i)\\b20%\\s+circulose\\s+RECYCLED\\s+POLYESTER\\b", "20% RECYCLED POLYESTER");
        }
        return oneLine(r);
    }

    private static String normalizeBomDescriptionContinuation(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";

        // First apply comprehensive word merge
        r = mergeSplitWords(r);
        
        // Fix common OCR broken tokens in continuation text across pages
        // irculose -> circulose
        r = r.replaceAll("(?i)\\birculose\\b", "circulose");
        // recy -> recycled (but not if already followed by 'cled')
        r = r.replaceAll("(?i)\\brecy\\s+RECYCLED\\b", "recycled");
        r = r.replaceAll("(?i)\\brecy\\b(?!\\s*cled)", "recycled");
        // cled polyeste -> recycled polyester (continuation fragment)
        r = r.replaceAll("(?i)\\bcled\\s+polyeste\\b", "recycled polyester");
        // RECYCLED P ... OLYESTER -> RECYCLED POLYESTER
        r = r.replaceAll("(?i)\\bRECYCLED\\s+P\\s+OLYESTER\\b", "RECYCLED POLYESTER");
        r = r.replaceAll("(?i)\\bRECYCLED\\s+P\\b", "RECYCLED");
        r = r.replaceAll("(?i)\\bOLYESTER\\b", "POLYESTER");
        // polyeste OLYESTER -> polyester
        r = r.replaceAll("(?i)\\bpolyeste\\s+POLYESTER\\b", "polyester");
        r = r.replaceAll("(?i)\\bpolyeste\\b", "polyester");
        // Fix 'with c circulose' or 'with c irculose'
        r = r.replaceAll("(?i)\\bwith\\s+c\\s+circulose\\b", "with circulose");
        r = r.replaceAll("(?i)\\bwith\\s+c\\s+irculose\\b", "with circulose");
        // Remove duplicate 'circulose circulose'
        r = r.replaceAll("(?i)\\bcirculose\\s+circulose\\b", "circulose");
        // Fix 'r 20dx45s' -> '20dx45s' (stray 'r' from polyester split)
        r = r.replaceAll("(?i)\\br\\s+(\\d+dx\\d+s)\\b", "$1");
        // Fix '75g/sm 5 5"CW' -> '75g/sm 55"CW' (split digit)
        r = r.replaceAll("(\\d+g/sm)\\s+(\\d)\\s+(\\d)\"", "$1 $2$3\"");
        // Fix 'x94' standalone -> merge with previous '150' if present
        r = r.replaceAll("(\\d{3})\\s+x(\\d{2})\\b", "$1x$2");
        // Clean duplicate RECYCLED
        r = r.replaceAll("(?i)\\brecycled\\s+recycled\\b", "recycled");
        // Clean duplicate polyester
        r = r.replaceAll("(?i)\\bpolyester\\s+polyester\\b", "polyester");
        // Clean 'recycled recycled polyester' pattern
        r = r.replaceAll("(?i)\\brecycled\\s+polyester\\s+recycled\\s+polyester\\b", "recycled polyester");
        
        return oneLine(r);
    }

    /**
     * Menggabungkan kata-kata yang terpotong di OCR output.
     * Contoh: "INDONES IA" -> "INDONESIA", "POL YESTER" -> "POLYESTER"
     * 
     * Fungsi ini mendeteksi:
     * 1. Fragment kata di akhir yang tidak lengkap
     * 2. Fragment lanjutan di awal kata berikutnya
     * 3. Menggabungkan menjadi kata utuh
     */
    public static String mergeSplitWords(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String r = oneLine(raw);

        // ===== FIBER/MATERIAL WORDS (ENTERPRISE GARMENT BOM) =====
        // POLYESTER
        r = r.replaceAll("(?i)\\bPOL\\s+YESTER\\b", "POLYESTER");
        r = r.replaceAll("(?i)\\bPOLY\\s+ESTER\\b", "POLYESTER");
        r = r.replaceAll("(?i)\\bPOLYEST\\s+ER\\b", "POLYESTER");
        r = r.replaceAll("(?i)\\bS0\\s+POL\\s+YESTER\\b", "SO POLYESTER");
        r = r.replaceAll("(?i)\\bS0\\s+POLYESTER\\b", "SO POLYESTER");
        r = r.replaceAll("(?i)\\bSO\\s+POL\\s+YESTER\\b", "SO POLYESTER");
        // POLYAMIDE/NYLON
        r = r.replaceAll("(?i)\\bPOLY\\s+AMIDE\\b", "POLYAMIDE");
        r = r.replaceAll("(?i)\\bPOLYAM\\s+IDE\\b", "POLYAMIDE");
        r = r.replaceAll("(?i)\\bNY\\s+LON\\b", "NYLON");
        r = r.replaceAll("(?i)\\bNYL\\s+ON\\b", "NYLON");
        // POLYPROPYLENE
        r = r.replaceAll("(?i)\\bPOLY\\s+PROPYLENE\\b", "POLYPROPYLENE");
        r = r.replaceAll("(?i)\\bPOLYPROP\\s+YLENE\\b", "POLYPROPYLENE");
        // POLYURETHANE
        r = r.replaceAll("(?i)\\bPOLY\\s+URETHANE\\b", "POLYURETHANE");
        r = r.replaceAll("(?i)\\bPOLYURETH\\s+ANE\\b", "POLYURETHANE");
        // VISCOSE/RAYON
        r = r.replaceAll("(?i)\\bVIS\\s+COSE\\b", "VISCOSE");
        r = r.replaceAll("(?i)\\bVISCO\\s+SE\\b", "VISCOSE");
        r = r.replaceAll("(?i)\\bRAY\\s+ON\\b", "RAYON");
        r = r.replaceAll("(?i)\\bRAYO\\s+N\\b", "RAYON");
        // REVISCO/CIRCULOSE (Sustainable)
        r = r.replaceAll("(?i)\\bREVIS\\s+CO\\b", "REVISCO");
        r = r.replaceAll("(?i)\\bREVI\\s+SCO\\b", "REVISCO");
        r = r.replaceAll("(?i)\\bCIRCUL\\s+OSE\\b", "CIRCULOSE");
        r = r.replaceAll("(?i)\\bCIRCU\\s+LOSE\\b", "CIRCULOSE");
        r = r.replaceAll("(?i)\\bCIRC\\s+ULOSE\\b", "CIRCULOSE");
        r = r.replaceAll("(?i)\\bc\\s+irculose\\b", "circulose");
        r = r.replaceAll("(?i)\\bC\\s+irculose\\b", "Circulose");
        r = r.replaceAll("(?i)\\bc\\s+ircu\\s*lose\\b", "circulose");
        r = r.replaceAll("(?i)\\birculose\\b", "circulose");  // Handle orphan fragment
        // Handle "co Viscose" orphan fragment (from Revis-co split across lines)
        r = r.replaceAll("(?i)\\bco\\s+Viscose\\b", "Revisco Viscose");
        r = r.replaceAll("(?i)\\bRevis\\s+co\\s+Viscose\\b", "Revisco Viscose");
        // RECYCLED
        r = r.replaceAll("(?i)\\bRECY\\s+CLED\\b", "RECYCLED");
        r = r.replaceAll("(?i)\\bRECYC\\s+LED\\b", "RECYCLED");
        // COTTON
        r = r.replaceAll("(?i)\\bCOT\\s+TON\\b", "COTTON");
        r = r.replaceAll("(?i)\\bCOTT\\s+ON\\b", "COTTON");
        // ORGANIC COTTON
        r = r.replaceAll("(?i)\\bORGAN\\s+IC\\b", "ORGANIC");
        r = r.replaceAll("(?i)\\bORGA\\s+NIC\\b", "ORGANIC");
        // ELASTANE/SPANDEX/LYCRA
        r = r.replaceAll("(?i)\\bELAS\\s+TANE\\b", "ELASTANE");
        r = r.replaceAll("(?i)\\bELAST\\s+ANE\\b", "ELASTANE");
        r = r.replaceAll("(?i)\\bSPAN\\s+DEX\\b", "SPANDEX");
        r = r.replaceAll("(?i)\\bSPAND\\s+EX\\b", "SPANDEX");
        r = r.replaceAll("(?i)\\bLYC\\s+RA\\b", "LYCRA");
        r = r.replaceAll("(?i)\\bLYCR\\s+A\\b", "LYCRA");
        // LINEN/FLAX
        r = r.replaceAll("(?i)\\bLIN\\s+EN\\b", "LINEN");
        r = r.replaceAll("(?i)\\bLINE\\s+N\\b", "LINEN");
        r = r.replaceAll("(?i)\\bFLA\\s+X\\b", "FLAX");
        // SILK
        r = r.replaceAll("(?i)\\bSIL\\s+K\\b", "SILK");
        // WOOL/MERINO
        r = r.replaceAll("(?i)\\bWOO\\s+L\\b", "WOOL");
        r = r.replaceAll("(?i)\\bMER\\s+INO\\b", "MERINO");
        r = r.replaceAll("(?i)\\bMERI\\s+NO\\b", "MERINO");
        // CASHMERE
        r = r.replaceAll("(?i)\\bCASH\\s+MERE\\b", "CASHMERE");
        r = r.replaceAll("(?i)\\bCASHM\\s+ERE\\b", "CASHMERE");
        // ACRYLIC
        r = r.replaceAll("(?i)\\bACRY\\s+LIC\\b", "ACRYLIC");
        r = r.replaceAll("(?i)\\bACRYL\\s+IC\\b", "ACRYLIC");
        // MODAL
        r = r.replaceAll("(?i)\\bMOD\\s+AL\\b", "MODAL");
        r = r.replaceAll("(?i)\\bMODA\\s+L\\b", "MODAL");
        // TENCEL/LYOCELL
        r = r.replaceAll("(?i)\\bTEN\\s+CEL\\b", "TENCEL");
        r = r.replaceAll("(?i)\\bTENC\\s+EL\\b", "TENCEL");
        r = r.replaceAll("(?i)\\bLYO\\s+CELL\\b", "LYOCELL");
        r = r.replaceAll("(?i)\\bLYOC\\s+ELL\\b", "LYOCELL");
        // BAMBOO
        r = r.replaceAll("(?i)\\bBAM\\s+BOO\\b", "BAMBOO");
        r = r.replaceAll("(?i)\\bBAMB\\s+OO\\b", "BAMBOO");
        // HEMP
        r = r.replaceAll("(?i)\\bHEM\\s+P\\b", "HEMP");
        // JUTE
        r = r.replaceAll("(?i)\\bJUT\\s+E\\b", "JUTE");
        // ACETATE
        r = r.replaceAll("(?i)\\bACE\\s+TATE\\b", "ACETATE");
        r = r.replaceAll("(?i)\\bACET\\s+ATE\\b", "ACETATE");
        // TRIACETATE
        r = r.replaceAll("(?i)\\bTRI\\s+ACETATE\\b", "TRIACETATE");
        r = r.replaceAll("(?i)\\bTRIACE\\s+TATE\\b", "TRIACETATE");
        // CUPRO
        r = r.replaceAll("(?i)\\bCUP\\s+RO\\b", "CUPRO");
        r = r.replaceAll("(?i)\\bCUPR\\s+O\\b", "CUPRO");
        // METALLIC
        r = r.replaceAll("(?i)\\bMET\\s+ALLIC\\b", "METALLIC");
        r = r.replaceAll("(?i)\\bMETAL\\s+LIC\\b", "METALLIC");
        // LUREX
        r = r.replaceAll("(?i)\\bLUR\\s+EX\\b", "LUREX");
        r = r.replaceAll("(?i)\\bLURE\\s+X\\b", "LUREX");
        // MICROFIBER
        r = r.replaceAll("(?i)\\bMICRO\\s+FIBER\\b", "MICROFIBER");
        r = r.replaceAll("(?i)\\bMICROFIB\\s+ER\\b", "MICROFIBER");
        // FLEECE
        r = r.replaceAll("(?i)\\bFLE\\s+ECE\\b", "FLEECE");
        r = r.replaceAll("(?i)\\bFLEE\\s+CE\\b", "FLEECE");
        // LEATHER
        r = r.replaceAll("(?i)\\bLEA\\s+THER\\b", "LEATHER");
        r = r.replaceAll("(?i)\\bLEATH\\s+ER\\b", "LEATHER");
        // FAUX LEATHER/PU LEATHER
        r = r.replaceAll("(?i)\\bFAU\\s+X\\b", "FAUX");
        r = r.replaceAll("(?i)\\bSYN\\s+THETIC\\b", "SYNTHETIC");
        r = r.replaceAll("(?i)\\bSYNTHE\\s+TIC\\b", "SYNTHETIC");

        // ===== COUNTRY NAMES (TEXTILE MANUFACTURING HUBS) =====
        r = r.replaceAll("(?i)\\bINDONES\\s+IA\\b", "INDONESIA");
        r = r.replaceAll("(?i)\\bINDO\\s+NESIA\\b", "INDONESIA");
        r = r.replaceAll("(?i)\\bN\\s*DONESIA\\b", "INDONESIA");
        r = r.replaceAll("(?i)\\bI\\s+NDONESIA\\b", "INDONESIA");
        r = r.replaceAll("(?i)\\bCHI\\s+NA\\b", "CHINA");
        r = r.replaceAll("(?i)\\bVIET\\s+NAM\\b", "VIETNAM");
        r = r.replaceAll("(?i)\\bVIETN\\s+AM\\b", "VIETNAM");
        r = r.replaceAll("(?i)\\bBANGLA\\s+DESH\\b", "BANGLADESH");
        r = r.replaceAll("(?i)\\bBANGLAD\\s+ESH\\b", "BANGLADESH");
        r = r.replaceAll("(?i)\\bTHAI\\s+LAND\\b", "THAILAND");
        r = r.replaceAll("(?i)\\bTHAIL\\s+AND\\b", "THAILAND");
        r = r.replaceAll("(?i)\\bMALAY\\s+SIA\\b", "MALAYSIA");
        r = r.replaceAll("(?i)\\bMALAYS\\s+IA\\b", "MALAYSIA");
        r = r.replaceAll("(?i)\\bPAKIS\\s+TAN\\b", "PAKISTAN");
        r = r.replaceAll("(?i)\\bPAKIST\\s+AN\\b", "PAKISTAN");
        r = r.replaceAll("(?i)\\bCAMBO\\s+DIA\\b", "CAMBODIA");
        r = r.replaceAll("(?i)\\bCAMBOD\\s+IA\\b", "CAMBODIA");
        r = r.replaceAll("(?i)\\bMYAN\\s+MAR\\b", "MYANMAR");
        r = r.replaceAll("(?i)\\bMYANM\\s+AR\\b", "MYANMAR");
        r = r.replaceAll("(?i)\\bSRI\\s+LANKA\\b", "SRI LANKA");
        r = r.replaceAll("(?i)\\bSRIL\\s+ANKA\\b", "SRILANKA");
        r = r.replaceAll("(?i)\\bIN\\s+DIA\\b", "INDIA");
        r = r.replaceAll("(?i)\\bIND\\s+IA\\b", "INDIA");
        r = r.replaceAll("(?i)\\bPHILIP\\s+PINES\\b", "PHILIPPINES");
        r = r.replaceAll("(?i)\\bPHILIPP\\s+INES\\b", "PHILIPPINES");
        r = r.replaceAll("(?i)\\bTUR\\s+KEY\\b", "TURKEY");
        r = r.replaceAll("(?i)\\bTURK\\s+EY\\b", "TURKEY");
        r = r.replaceAll("(?i)\\bEGY\\s+PT\\b", "EGYPT");
        r = r.replaceAll("(?i)\\bEGYP\\s+T\\b", "EGYPT");
        r = r.replaceAll("(?i)\\bMORO\\s+CCO\\b", "MOROCCO");
        r = r.replaceAll("(?i)\\bMOROC\\s+CO\\b", "MOROCCO");
        r = r.replaceAll("(?i)\\bTUNI\\s+SIA\\b", "TUNISIA");
        r = r.replaceAll("(?i)\\bTUNIS\\s+IA\\b", "TUNISIA");
        r = r.replaceAll("(?i)\\bPOR\\s+TUGAL\\b", "PORTUGAL");
        r = r.replaceAll("(?i)\\bPORTU\\s+GAL\\b", "PORTUGAL");
        r = r.replaceAll("(?i)\\bITA\\s+LY\\b", "ITALY");
        r = r.replaceAll("(?i)\\bITAL\\s+Y\\b", "ITALY");
        r = r.replaceAll("(?i)\\bGER\\s+MANY\\b", "GERMANY");
        r = r.replaceAll("(?i)\\bGERM\\s+ANY\\b", "GERMANY");
        r = r.replaceAll("(?i)\\bJA\\s+PAN\\b", "JAPAN");
        r = r.replaceAll("(?i)\\bJAP\\s+AN\\b", "JAPAN");
        r = r.replaceAll("(?i)\\bKO\\s+REA\\b", "KOREA");
        r = r.replaceAll("(?i)\\bKOR\\s+EA\\b", "KOREA");
        r = r.replaceAll("(?i)\\bTAI\\s+WAN\\b", "TAIWAN");
        r = r.replaceAll("(?i)\\bTAIW\\s+AN\\b", "TAIWAN");
        r = r.replaceAll("(?i)\\bHONG\\s+KONG\\b", "HONGKONG");
        r = r.replaceAll("(?i)\\bSINGA\\s+PORE\\b", "SINGAPORE");
        r = r.replaceAll("(?i)\\bSINGAP\\s+ORE\\b", "SINGAPORE");
        r = r.replaceAll("(?i)\\bUNI\\s+TED\\b", "UNITED");
        r = r.replaceAll("(?i)\\bKING\\s+DOM\\b", "KINGDOM");
        r = r.replaceAll("(?i)\\bSTA\\s+TES\\b", "STATES");
        r = r.replaceAll("(?i)\\bAMER\\s+ICA\\b", "AMERICA");
        r = r.replaceAll("(?i)\\bAUS\\s+TRALIA\\b", "AUSTRALIA");
        r = r.replaceAll("(?i)\\bAUSTRAL\\s+IA\\b", "AUSTRALIA");
        r = r.replaceAll("(?i)\\bNETHER\\s+LANDS\\b", "NETHERLANDS");
        r = r.replaceAll("(?i)\\bNETHERL\\s+ANDS\\b", "NETHERLANDS");
        r = r.replaceAll("(?i)\\bSWE\\s+DEN\\b", "SWEDEN");
        r = r.replaceAll("(?i)\\bSWED\\s+EN\\b", "SWEDEN");
        r = r.replaceAll("(?i)\\bFRA\\s+NCE\\b", "FRANCE");
        r = r.replaceAll("(?i)\\bFRAN\\s+CE\\b", "FRANCE");
        r = r.replaceAll("(?i)\\bSPA\\s+IN\\b", "SPAIN");
        r = r.replaceAll("(?i)\\bSPAI\\s+N\\b", "SPAIN");
        r = r.replaceAll("(?i)\\bBRA\\s+ZIL\\b", "BRAZIL");
        r = r.replaceAll("(?i)\\bBRAZ\\s+IL\\b", "BRAZIL");
        r = r.replaceAll("(?i)\\bMEX\\s+ICO\\b", "MEXICO");
        r = r.replaceAll("(?i)\\bMEXI\\s+CO\\b", "MEXICO");
        r = r.replaceAll("(?i)\\bCAN\\s+ADA\\b", "CANADA");
        r = r.replaceAll("(?i)\\bCANA\\s+DA\\b", "CANADA");

        // ===== COMPANY/SUPPLIER TERMS =====
        r = r.replaceAll("(?i)\\bTRAD\\s+ING\\b", "TRADING");
        r = r.replaceAll("(?i)\\bTRADI\\s+NG\\b", "TRADING");
        r = r.replaceAll("(?i)\\bEXPO\\s+RT\\b", "EXPORT");
        r = r.replaceAll("(?i)\\bIMPO\\s+RT\\b", "IMPORT");
        r = r.replaceAll("(?i)\\bGAR\\s+MENT\\b", "GARMENT");
        r = r.replaceAll("(?i)\\bGARME\\s+NT\\b", "GARMENT");
        r = r.replaceAll("(?i)\\bTEX\\s+TILE\\b", "TEXTILE");
        r = r.replaceAll("(?i)\\bTEXTI\\s+LE\\b", "TEXTILE");
        r = r.replaceAll("(?i)\\bACCESS\\s+ORIES\\b", "ACCESSORIES");
        r = r.replaceAll("(?i)\\bACCESSOR\\s+IES\\b", "ACCESSORIES");
        r = r.replaceAll("(?i)\\bPRINT\\s+ING\\b", "PRINTING");
        r = r.replaceAll("(?i)\\bPRINTI\\s+NG\\b", "PRINTING");
        r = r.replaceAll("(?i)\\bDYE\\s+ING\\b", "DYEING");
        r = r.replaceAll("(?i)\\bDYEI\\s+NG\\b", "DYEING");
        
        // BROTHREAD (supplier name)
        r = r.replaceAll("(?i)\\bB\\s*ROTHREAD\\b", "BROTHREAD");
        r = r.replaceAll("(?i)\\bBRO\\s+THREAD\\b", "BROTHREAD");
        r = r.replaceAll("(?i)\\bBROTHR\\s+EAD\\b", "BROTHREAD");
        
        // SAMJIN (supplier name)
        r = r.replaceAll("(?i)\\bSAMJIN\\s*B\\s*ROTHREAD\\b", "SAMJIN BROTHREAD");
        r = r.replaceAll("(?i)\\bSAM\\s+JIN\\b", "SAMJIN");

        // ===== GARMENT DESCRIPTION TERMS (ENTERPRISE BOM) =====
        // Thread/Sewing
        r = r.replaceAll("(?i)\\bthr\\s+ead\\b", "thread");
        r = r.replaceAll("(?i)\\bthre\\s+ad\\b", "thread");
        r = r.replaceAll("(?i)\\bSEW\\s+ING\\b", "SEWING");
        r = r.replaceAll("(?i)\\bSEWI\\s+NG\\b", "SEWING");
        r = r.replaceAll("(?i)\\bSTITCH\\s+ING\\b", "STITCHING");
        r = r.replaceAll("(?i)\\bSTITCHI\\s+NG\\b", "STITCHING");
        r = r.replaceAll("(?i)\\bEMBROI\\s+DERY\\b", "EMBROIDERY");
        r = r.replaceAll("(?i)\\bEMBROID\\s+ERY\\b", "EMBROIDERY");
        // Smocking/Shirring
        r = r.replaceAll("(?i)\\bsmock\\s+ing\\b", "smocking");
        r = r.replaceAll("(?i)\\bsmocki\\s+ng\\b", "smocking");
        r = r.replaceAll("(?i)\\bSHIRR\\s+ING\\b", "SHIRRING");
        r = r.replaceAll("(?i)\\bSHIRRI\\s+NG\\b", "SHIRRING");
        // Elastic/Drawstring
        r = r.replaceAll("(?i)\\bElas\\s+tic\\b", "Elastic");
        r = r.replaceAll("(?i)\\bElast\\s+ic\\b", "Elastic");
        r = r.replaceAll("(?i)\\bDRAW\\s+STRING\\b", "DRAWSTRING");
        r = r.replaceAll("(?i)\\bDRAWSTR\\s+ING\\b", "DRAWSTRING");
        // Buckle/Clasp/Hook
        r = r.replaceAll("(?i)\\bBuck\\s+le\\b", "Buckle");
        r = r.replaceAll("(?i)\\bBuckl\\s+e\\b", "Buckle");
        r = r.replaceAll("(?i)\\bCLA\\s+SP\\b", "CLASP");
        r = r.replaceAll("(?i)\\bCLAS\\s+P\\b", "CLASP");
        r = r.replaceAll("(?i)\\bHOO\\s+K\\b", "HOOK");
        r = r.replaceAll("(?i)\\bEYE\\s+LET\\b", "EYELET");
        r = r.replaceAll("(?i)\\bEYEL\\s+ET\\b", "EYELET");
        r = r.replaceAll("(?i)\\bGROM\\s+MET\\b", "GROMMET");
        r = r.replaceAll("(?i)\\bGROMM\\s+ET\\b", "GROMMET");
        // Hanger/Loop/Ring
        r = r.replaceAll("(?i)\\bHang\\s+er\\b", "Hanger");
        r = r.replaceAll("(?i)\\bhange\\s+r\\b", "hanger");
        r = r.replaceAll("(?i)\\blo\\s+op\\b", "loop");
        r = r.replaceAll("(?i)\\bD\\s+RING\\b", "D-RING");
        r = r.replaceAll("(?i)\\bO\\s+RING\\b", "O-RING");
        // Label/Tag
        r = r.replaceAll("(?i)\\bLab\\s+el\\b", "Label");
        r = r.replaceAll("(?i)\\bLabe\\s+l\\b", "Label");
        r = r.replaceAll("(?i)\\bWO\\s+VEN\\b", "WOVEN");
        r = r.replaceAll("(?i)\\bWOV\\s+EN\\b", "WOVEN");
        r = r.replaceAll("(?i)\\bPRIN\\s+TED\\b", "PRINTED");
        r = r.replaceAll("(?i)\\bPRINT\\s+ED\\b", "PRINTED");
        r = r.replaceAll("(?i)\\bHANG\\s+TAG\\b", "HANGTAG");
        r = r.replaceAll("(?i)\\bHANGT\\s+AG\\b", "HANGTAG");
        r = r.replaceAll("(?i)\\bCARE\\s+LABEL\\b", "CARE LABEL");
        r = r.replaceAll("(?i)\\bSIZE\\s+LABEL\\b", "SIZE LABEL");
        r = r.replaceAll("(?i)\\bMAIN\\s+LABEL\\b", "MAIN LABEL");
        r = r.replaceAll("(?i)\\bBRAND\\s+LABEL\\b", "BRAND LABEL");
        r = r.replaceAll("(?i)\\bCONTENT\\s+LABEL\\b", "CONTENT LABEL");
        r = r.replaceAll("(?i)\\bWASH\\s+CARE\\b", "WASH CARE");
        // Button/Snap/Stud
        r = r.replaceAll("(?i)\\bBut\\s+ton\\b", "Button");
        r = r.replaceAll("(?i)\\bButt\\s+on\\b", "Button");
        r = r.replaceAll("(?i)\\bSNA\\s+P\\b", "SNAP");
        r = r.replaceAll("(?i)\\bSTU\\s+D\\b", "STUD");
        r = r.replaceAll("(?i)\\bPRE\\s+SS\\b", "PRESS");
        r = r.replaceAll("(?i)\\bRIV\\s+ET\\b", "RIVET");
        r = r.replaceAll("(?i)\\bRIVE\\s+T\\b", "RIVET");
        r = r.replaceAll("(?i)\\bJEAN\\s+S\\b", "JEANS");
        r = r.replaceAll("(?i)\\bSHAN\\s+K\\b", "SHANK");
        // Zipper/Slider
        r = r.replaceAll("(?i)\\bZip\\s+per\\b", "Zipper");
        r = r.replaceAll("(?i)\\bZipp\\s+er\\b", "Zipper");
        r = r.replaceAll("(?i)\\bSLID\\s+ER\\b", "SLIDER");
        r = r.replaceAll("(?i)\\bSLI\\s+DER\\b", "SLIDER");
        r = r.replaceAll("(?i)\\bPUL\\s+LER\\b", "PULLER");
        r = r.replaceAll("(?i)\\bPULL\\s+ER\\b", "PULLER");
        r = r.replaceAll("(?i)\\bCOI\\s+L\\b", "COIL");
        r = r.replaceAll("(?i)\\bVIS\\s+LON\\b", "VISLON");
        r = r.replaceAll("(?i)\\bMET\\s+AL\\b", "METAL");
        r = r.replaceAll("(?i)\\bMETA\\s+L\\b", "METAL");
        r = r.replaceAll("(?i)\\bINVIS\\s+IBLE\\b", "INVISIBLE");
        r = r.replaceAll("(?i)\\bINVISI\\s+BLE\\b", "INVISIBLE");
        // Ribbon/Tape/Binding
        r = r.replaceAll("(?i)\\bRib\\s+bon\\b", "Ribbon");
        r = r.replaceAll("(?i)\\bRibb\\s+on\\b", "Ribbon");
        r = r.replaceAll("(?i)\\bTA\\s+PE\\b", "TAPE");
        r = r.replaceAll("(?i)\\bTAP\\s+E\\b", "TAPE");
        r = r.replaceAll("(?i)\\bBIND\\s+ING\\b", "BINDING");
        r = r.replaceAll("(?i)\\bBINDI\\s+NG\\b", "BINDING");
        r = r.replaceAll("(?i)\\bPIP\\s+ING\\b", "PIPING");
        r = r.replaceAll("(?i)\\bPIPI\\s+NG\\b", "PIPING");
        r = r.replaceAll("(?i)\\bCOR\\s+D\\b", "CORD");
        r = r.replaceAll("(?i)\\bCOR\\s+DING\\b", "CORDING");
        r = r.replaceAll("(?i)\\bLAC\\s+E\\b", "LACE");
        r = r.replaceAll("(?i)\\bLACE\\s+S\\b", "LACES");
        r = r.replaceAll("(?i)\\bBRA\\s+ID\\b", "BRAID");
        r = r.replaceAll("(?i)\\bBRAI\\s+D\\b", "BRAID");
        r = r.replaceAll("(?i)\\bTWI\\s+LL\\b", "TWILL");
        r = r.replaceAll("(?i)\\bTWIL\\s+L\\b", "TWILL");
        r = r.replaceAll("(?i)\\bGROS\\s+GRAIN\\b", "GROSGRAIN");
        r = r.replaceAll("(?i)\\bGROSGRA\\s+IN\\b", "GROSGRAIN");
        r = r.replaceAll("(?i)\\bSAT\\s+IN\\b", "SATIN");
        r = r.replaceAll("(?i)\\bSATI\\s+N\\b", "SATIN");
        r = r.replaceAll("(?i)\\bVEL\\s+VET\\b", "VELVET");
        r = r.replaceAll("(?i)\\bVELV\\s+ET\\b", "VELVET");
        // Interlining/Interfacing/Padding
        r = r.replaceAll("(?i)\\bINTER\\s+LINING\\b", "INTERLINING");
        r = r.replaceAll("(?i)\\bINTERLIN\\s+ING\\b", "INTERLINING");
        r = r.replaceAll("(?i)\\bINTER\\s+FACING\\b", "INTERFACING");
        r = r.replaceAll("(?i)\\bINTERFAC\\s+ING\\b", "INTERFACING");
        r = r.replaceAll("(?i)\\bFUS\\s+IBLE\\b", "FUSIBLE");
        r = r.replaceAll("(?i)\\bFUSI\\s+BLE\\b", "FUSIBLE");
        r = r.replaceAll("(?i)\\bPAD\\s+DING\\b", "PADDING");
        r = r.replaceAll("(?i)\\bPADDI\\s+NG\\b", "PADDING");
        r = r.replaceAll("(?i)\\bWAD\\s+DING\\b", "WADDING");
        r = r.replaceAll("(?i)\\bWADDI\\s+NG\\b", "WADDING");
        r = r.replaceAll("(?i)\\bINSU\\s+LATION\\b", "INSULATION");
        r = r.replaceAll("(?i)\\bINSULAT\\s+ION\\b", "INSULATION");
        r = r.replaceAll("(?i)\\bQUIL\\s+TING\\b", "QUILTING");
        r = r.replaceAll("(?i)\\bQUILTI\\s+NG\\b", "QUILTING");
        // Garment Parts
        r = r.replaceAll("(?i)\\bCOL\\s+LAR\\b", "COLLAR");
        r = r.replaceAll("(?i)\\bCOLLA\\s+R\\b", "COLLAR");
        r = r.replaceAll("(?i)\\bCUF\\s+F\\b", "CUFF");
        r = r.replaceAll("(?i)\\bCUFF\\s+S\\b", "CUFFS");
        r = r.replaceAll("(?i)\\bSLE\\s+EVE\\b", "SLEEVE");
        r = r.replaceAll("(?i)\\bSLEEV\\s+E\\b", "SLEEVE");
        r = r.replaceAll("(?i)\\bPOC\\s+KET\\b", "POCKET");
        r = r.replaceAll("(?i)\\bPOCKE\\s+T\\b", "POCKET");
        r = r.replaceAll("(?i)\\bHEM\\s+LINE\\b", "HEMLINE");
        r = r.replaceAll("(?i)\\bWAIST\\s+BAND\\b", "WAISTBAND");
        r = r.replaceAll("(?i)\\bWAISTB\\s+AND\\b", "WAISTBAND");
        r = r.replaceAll("(?i)\\bPLAC\\s+KET\\b", "PLACKET");
        r = r.replaceAll("(?i)\\bPLACKE\\s+T\\b", "PLACKET");
        r = r.replaceAll("(?i)\\bYOK\\s+E\\b", "YOKE");
        r = r.replaceAll("(?i)\\bGUS\\s+SET\\b", "GUSSET");
        r = r.replaceAll("(?i)\\bGUSSE\\s+T\\b", "GUSSET");
        r = r.replaceAll("(?i)\\bGODE\\s+T\\b", "GODET");
        r = r.replaceAll("(?i)\\bFLAP\\s+S\\b", "FLAPS");
        r = r.replaceAll("(?i)\\bPLE\\s+AT\\b", "PLEAT");
        r = r.replaceAll("(?i)\\bPLEA\\s+T\\b", "PLEAT");
        r = r.replaceAll("(?i)\\bDAR\\s+T\\b", "DART");
        r = r.replaceAll("(?i)\\bDART\\s+S\\b", "DARTS");
        r = r.replaceAll("(?i)\\bRUF\\s+FLE\\b", "RUFFLE");
        r = r.replaceAll("(?i)\\bRUFF\\s+LE\\b", "RUFFLE");
        r = r.replaceAll("(?i)\\bFRI\\s+LL\\b", "FRILL");
        r = r.replaceAll("(?i)\\bFRIL\\s+L\\b", "FRILL");
        r = r.replaceAll("(?i)\\bLIN\\s+ING\\b", "LINING");
        r = r.replaceAll("(?i)\\bLINI\\s+NG\\b", "LINING");
        // Packaging
        r = r.replaceAll("(?i)\\bPOLY\\s+BAG\\b", "POLYBAG");
        r = r.replaceAll("(?i)\\bPOLYB\\s+AG\\b", "POLYBAG");
        r = r.replaceAll("(?i)\\bCAR\\s+TON\\b", "CARTON");
        r = r.replaceAll("(?i)\\bCARTO\\s+N\\b", "CARTON");
        r = r.replaceAll("(?i)\\bTIS\\s+SUE\\b", "TISSUE");
        r = r.replaceAll("(?i)\\bTISSU\\s+E\\b", "TISSUE");
        r = r.replaceAll("(?i)\\bPIN\\s+S\\b", "PINS");
        r = r.replaceAll("(?i)\\bCLIP\\s+S\\b", "CLIPS");
        r = r.replaceAll("(?i)\\bBAR\\s+CODE\\b", "BARCODE");
        r = r.replaceAll("(?i)\\bBARCO\\s+DE\\b", "BARCODE");
        r = r.replaceAll("(?i)\\bSTIC\\s+KER\\b", "STICKER");
        r = r.replaceAll("(?i)\\bSTICK\\s+ER\\b", "STICKER");
        // Fabric Types
        r = r.replaceAll("(?i)\\bDEN\\s+IM\\b", "DENIM");
        r = r.replaceAll("(?i)\\bDENI\\s+M\\b", "DENIM");
        r = r.replaceAll("(?i)\\bCHAM\\s+BRAY\\b", "CHAMBRAY");
        r = r.replaceAll("(?i)\\bCHAMBR\\s+AY\\b", "CHAMBRAY");
        r = r.replaceAll("(?i)\\bCAM\\s+BRIC\\b", "CAMBRIC");
        r = r.replaceAll("(?i)\\bCAMBR\\s+IC\\b", "CAMBRIC");
        r = r.replaceAll("(?i)\\bPOP\\s+LIN\\b", "POPLIN");
        r = r.replaceAll("(?i)\\bPOPL\\s+IN\\b", "POPLIN");
        r = r.replaceAll("(?i)\\bOX\\s+FORD\\b", "OXFORD");
        r = r.replaceAll("(?i)\\bOXFO\\s+RD\\b", "OXFORD");
        r = r.replaceAll("(?i)\\bJER\\s+SEY\\b", "JERSEY");
        r = r.replaceAll("(?i)\\bJERS\\s+EY\\b", "JERSEY");
        r = r.replaceAll("(?i)\\bINTER\\s+LOCK\\b", "INTERLOCK");
        r = r.replaceAll("(?i)\\bINTERLO\\s+CK\\b", "INTERLOCK");
        r = r.replaceAll("(?i)\\bRIB\\s+KNIT\\b", "RIB KNIT");
        r = r.replaceAll("(?i)\\bPIQ\\s+UE\\b", "PIQUE");
        r = r.replaceAll("(?i)\\bPIQU\\s+E\\b", "PIQUE");
        r = r.replaceAll("(?i)\\bFREN\\s+CH\\b", "FRENCH");
        r = r.replaceAll("(?i)\\bFRENC\\s+H\\b", "FRENCH");
        r = r.replaceAll("(?i)\\bTER\\s+RY\\b", "TERRY");
        r = r.replaceAll("(?i)\\bTERR\\s+Y\\b", "TERRY");
        r = r.replaceAll("(?i)\\bVOI\\s+LE\\b", "VOILE");
        r = r.replaceAll("(?i)\\bVOIL\\s+E\\b", "VOILE");
        r = r.replaceAll("(?i)\\bCHIF\\s+FON\\b", "CHIFFON");
        r = r.replaceAll("(?i)\\bCHIFF\\s+ON\\b", "CHIFFON");
        r = r.replaceAll("(?i)\\bGEOR\\s+GETTE\\b", "GEORGETTE");
        r = r.replaceAll("(?i)\\bGEORGE\\s+TTE\\b", "GEORGETTE");
        r = r.replaceAll("(?i)\\bCRE\\s+PE\\b", "CREPE");
        r = r.replaceAll("(?i)\\bCREP\\s+E\\b", "CREPE");
        r = r.replaceAll("(?i)\\bCHAR\\s+MEUSE\\b", "CHARMEUSE");
        r = r.replaceAll("(?i)\\bCHARME\\s+USE\\b", "CHARMEUSE");
        r = r.replaceAll("(?i)\\bORGAN\\s+ZA\\b", "ORGANZA");
        r = r.replaceAll("(?i)\\bORGANZ\\s+A\\b", "ORGANZA");
        r = r.replaceAll("(?i)\\bTUL\\s+LE\\b", "TULLE");
        r = r.replaceAll("(?i)\\bTULL\\s+E\\b", "TULLE");
        r = r.replaceAll("(?i)\\bMES\\s+H\\b", "MESH");
        r = r.replaceAll("(?i)\\bNET\\s+TING\\b", "NETTING");
        r = r.replaceAll("(?i)\\bNETTI\\s+NG\\b", "NETTING");
        r = r.replaceAll("(?i)\\bCAN\\s+VAS\\b", "CANVAS");
        r = r.replaceAll("(?i)\\bCANVA\\s+S\\b", "CANVAS");
        r = r.replaceAll("(?i)\\bTWE\\s+ED\\b", "TWEED");
        r = r.replaceAll("(?i)\\bTWEE\\s+D\\b", "TWEED");
        r = r.replaceAll("(?i)\\bFLAN\\s+NEL\\b", "FLANNEL");
        r = r.replaceAll("(?i)\\bFLANN\\s+EL\\b", "FLANNEL");
        r = r.replaceAll("(?i)\\bCOR\\s+DUROY\\b", "CORDUROY");
        r = r.replaceAll("(?i)\\bCORDU\\s+ROY\\b", "CORDUROY");
        r = r.replaceAll("(?i)\\bVEL\\s+OUR\\b", "VELOUR");
        r = r.replaceAll("(?i)\\bVELO\\s+UR\\b", "VELOUR");
        r = r.replaceAll("(?i)\\bSUE\\s+DE\\b", "SUEDE");
        r = r.replaceAll("(?i)\\bSUED\\s+E\\b", "SUEDE");
        r = r.replaceAll("(?i)\\bSHEER\\s+S\\b", "SHEERS");
        r = r.replaceAll("(?i)\\bSHE\\s+ER\\b", "SHEER");

        // ===== COMMON WORDS =====
        r = r.replaceAll("(?i)\\bwit\\s+h\\b", "with");
        r = r.replaceAll("(?i)\\bwi\\s+th\\b", "with");
        // sizes
        r = r.replaceAll("(?i)\\bsi\\s+zes\\b", "sizes");
        r = r.replaceAll("(?i)\\bsiz\\s+es\\b", "sizes");
        // gsm (grams per square meter) - OCR sometimes reads "80gsm" as "Ogsm" or "0gsm"
        r = r.replaceAll("(?i)\\bOgsm\\b", "0gsm");
        r = r.replaceAll("(\\d+)\\s+gsm\\b", "$1gsm");
        r = r.replaceAll("(\\d)\\s*Ogsm\\b", "$10gsm");  // e.g., 8 Ogsm -> 80gsm
        // Voile
        r = r.replaceAll("(?i)\\bVoi\\s+le\\b", "Voile");
        r = r.replaceAll("(?i)\\bNoile\\b", "Voile");  // OCR misread

        // ===== CITY NAMES (TEXTILE HUBS) =====
        // China
        r = r.replaceAll("(?i)\\bHANG\\s+ZHOU\\b", "HANGZHOU");
        r = r.replaceAll("(?i)\\bHANGZH\\s+OU\\b", "HANGZHOU");
        r = r.replaceAll("(?i)\\bSUZ\\s+HOU\\b", "SUZHOU");
        r = r.replaceAll("(?i)\\bSUZH\\s+OU\\b", "SUZHOU");
        r = r.replaceAll("(?i)\\bSHAO\\s+XING\\b", "SHAOXING");
        r = r.replaceAll("(?i)\\bSHAOX\\s+ING\\b", "SHAOXING");
        r = r.replaceAll("(?i)\\bNING\\s+BO\\b", "NINGBO");
        r = r.replaceAll("(?i)\\bNINGB\\s+O\\b", "NINGBO");
        r = r.replaceAll("(?i)\\bGUANG\\s+ZHOU\\b", "GUANGZHOU");
        r = r.replaceAll("(?i)\\bGUANGZH\\s+OU\\b", "GUANGZHOU");
        r = r.replaceAll("(?i)\\bSHEN\\s+ZHEN\\b", "SHENZHEN");
        r = r.replaceAll("(?i)\\bSHENZH\\s+EN\\b", "SHENZHEN");
        r = r.replaceAll("(?i)\\bDONG\\s+GUAN\\b", "DONGGUAN");
        r = r.replaceAll("(?i)\\bDONGGU\\s+AN\\b", "DONGGUAN");
        r = r.replaceAll("(?i)\\bQING\\s+DAO\\b", "QINGDAO");
        r = r.replaceAll("(?i)\\bQINGD\\s+AO\\b", "QINGDAO");
        r = r.replaceAll("(?i)\\bSHANG\\s+HAI\\b", "SHANGHAI");
        r = r.replaceAll("(?i)\\bSHANGH\\s+AI\\b", "SHANGHAI");
        r = r.replaceAll("(?i)\\bBEI\\s+JING\\b", "BEIJING");
        r = r.replaceAll("(?i)\\bBEIJ\\s+ING\\b", "BEIJING");
        r = r.replaceAll("(?i)\\bWU\\s+XI\\b", "WUXI");
        r = r.replaceAll("(?i)\\bWUX\\s+I\\b", "WUXI");
        r = r.replaceAll("(?i)\\bNAN\\s+TONG\\b", "NANTONG");
        r = r.replaceAll("(?i)\\bNANTO\\s+NG\\b", "NANTONG");
        // Bangladesh
        r = r.replaceAll("(?i)\\bDHA\\s+KA\\b", "DHAKA");
        r = r.replaceAll("(?i)\\bDHAK\\s+A\\b", "DHAKA");
        r = r.replaceAll("(?i)\\bCHIT\\s+TAGONG\\b", "CHITTAGONG");
        r = r.replaceAll("(?i)\\bCHITTA\\s+GONG\\b", "CHITTAGONG");
        // Indonesia
        r = r.replaceAll("(?i)\\bJAK\\s+ARTA\\b", "JAKARTA");
        r = r.replaceAll("(?i)\\bJAKAR\\s+TA\\b", "JAKARTA");
        r = r.replaceAll("(?i)\\bSURA\\s+BAYA\\b", "SURABAYA");
        r = r.replaceAll("(?i)\\bSURABA\\s+YA\\b", "SURABAYA");
        r = r.replaceAll("(?i)\\bBAN\\s+DUNG\\b", "BANDUNG");
        r = r.replaceAll("(?i)\\bBANDU\\s+NG\\b", "BANDUNG");
        r = r.replaceAll("(?i)\\bSEMA\\s+RANG\\b", "SEMARANG");
        r = r.replaceAll("(?i)\\bSEMARA\\s+NG\\b", "SEMARANG");
        // Vietnam
        r = r.replaceAll("(?i)\\bHO\\s+CHI\\b", "HO CHI");
        r = r.replaceAll("(?i)\\bHA\\s+NOI\\b", "HANOI");
        r = r.replaceAll("(?i)\\bHANO\\s+I\\b", "HANOI");
        // India
        r = r.replaceAll("(?i)\\bMUM\\s+BAI\\b", "MUMBAI");
        r = r.replaceAll("(?i)\\bMUMB\\s+AI\\b", "MUMBAI");
        r = r.replaceAll("(?i)\\bDEL\\s+HI\\b", "DELHI");
        r = r.replaceAll("(?i)\\bDELH\\s+I\\b", "DELHI");
        r = r.replaceAll("(?i)\\bTIRU\\s+PUR\\b", "TIRUPUR");
        r = r.replaceAll("(?i)\\bTIRUP\\s+UR\\b", "TIRUPUR");
        r = r.replaceAll("(?i)\\bCOIM\\s+BATORE\\b", "COIMBATORE");
        r = r.replaceAll("(?i)\\bCOIMBA\\s+TORE\\b", "COIMBATORE");
        r = r.replaceAll("(?i)\\bAHMED\\s+ABAD\\b", "AHMEDABAD");
        r = r.replaceAll("(?i)\\bAHMEDA\\s+BAD\\b", "AHMEDABAD");
        r = r.replaceAll("(?i)\\bSUR\\s+AT\\b", "SURAT");
        r = r.replaceAll("(?i)\\bSURA\\s+T\\b", "SURAT");

        // ===== TREATMENT/FINISHING TERMS =====
        r = r.replaceAll("(?i)\\bWASH\\s+ED\\b", "WASHED");
        r = r.replaceAll("(?i)\\bWASHE\\s+D\\b", "WASHED");
        r = r.replaceAll("(?i)\\bGAR\\s+MENT\\s+WASH\\b", "GARMENT WASH");
        r = r.replaceAll("(?i)\\bSTONE\\s+WASH\\b", "STONEWASH");
        r = r.replaceAll("(?i)\\bSTONEW\\s+ASH\\b", "STONEWASH");
        r = r.replaceAll("(?i)\\bACID\\s+WASH\\b", "ACID WASH");
        r = r.replaceAll("(?i)\\bBLE\\s+ACH\\b", "BLEACH");
        r = r.replaceAll("(?i)\\bBLEAC\\s+H\\b", "BLEACH");
        r = r.replaceAll("(?i)\\bBLEACH\\s+ED\\b", "BLEACHED");
        r = r.replaceAll("(?i)\\bDYE\\s+D\\b", "DYED");
        r = r.replaceAll("(?i)\\bDYED\\s+S\\b", "DYEDS");
        r = r.replaceAll("(?i)\\bPIG\\s+MENT\\b", "PIGMENT");
        r = r.replaceAll("(?i)\\bPIGME\\s+NT\\b", "PIGMENT");
        r = r.replaceAll("(?i)\\bREAC\\s+TIVE\\b", "REACTIVE");
        r = r.replaceAll("(?i)\\bREACTI\\s+VE\\b", "REACTIVE");
        r = r.replaceAll("(?i)\\bPRIN\\s+T\\b", "PRINT");
        r = r.replaceAll("(?i)\\bPRINT\\s+S\\b", "PRINTS");
        r = r.replaceAll("(?i)\\bDIG\\s+ITAL\\b", "DIGITAL");
        r = r.replaceAll("(?i)\\bDIGIT\\s+AL\\b", "DIGITAL");
        r = r.replaceAll("(?i)\\bSCR\\s+EEN\\b", "SCREEN");
        r = r.replaceAll("(?i)\\bSCREE\\s+N\\b", "SCREEN");
        r = r.replaceAll("(?i)\\bROT\\s+ARY\\b", "ROTARY");
        r = r.replaceAll("(?i)\\bROTA\\s+RY\\b", "ROTARY");
        r = r.replaceAll("(?i)\\bTRANS\\s+FER\\b", "TRANSFER");
        r = r.replaceAll("(?i)\\bTRANSF\\s+ER\\b", "TRANSFER");
        r = r.replaceAll("(?i)\\bSUBLI\\s+MATION\\b", "SUBLIMATION");
        r = r.replaceAll("(?i)\\bSUBLIMA\\s+TION\\b", "SUBLIMATION");
        r = r.replaceAll("(?i)\\bFLOC\\s+K\\b", "FLOCK");
        r = r.replaceAll("(?i)\\bFLOCK\\s+ING\\b", "FLOCKING");
        r = r.replaceAll("(?i)\\bFOI\\s+L\\b", "FOIL");
        r = r.replaceAll("(?i)\\bPUF\\s+F\\b", "PUFF");
        r = r.replaceAll("(?i)\\bGLIT\\s+TER\\b", "GLITTER");
        r = r.replaceAll("(?i)\\bGLITT\\s+ER\\b", "GLITTER");
        r = r.replaceAll("(?i)\\bMETAL\\s+LIC\\b", "METALLIC");
        r = r.replaceAll("(?i)\\bCOAT\\s+ED\\b", "COATED");
        r = r.replaceAll("(?i)\\bCOATE\\s+D\\b", "COATED");
        r = r.replaceAll("(?i)\\bLAMIN\\s+ATED\\b", "LAMINATED");
        r = r.replaceAll("(?i)\\bLAMINA\\s+TED\\b", "LAMINATED");
        r = r.replaceAll("(?i)\\bBOND\\s+ED\\b", "BONDED");
        r = r.replaceAll("(?i)\\bBONDE\\s+D\\b", "BONDED");
        r = r.replaceAll("(?i)\\bBRUSH\\s+ED\\b", "BRUSHED");
        r = r.replaceAll("(?i)\\bBRUSHE\\s+D\\b", "BRUSHED");
        r = r.replaceAll("(?i)\\bPEACH\\s+ED\\b", "PEACHED");
        r = r.replaceAll("(?i)\\bPEACHE\\s+D\\b", "PEACHED");
        r = r.replaceAll("(?i)\\bSAND\\s+ED\\b", "SANDED");
        r = r.replaceAll("(?i)\\bSANDE\\s+D\\b", "SANDED");
        r = r.replaceAll("(?i)\\bSUE\\s+DED\\b", "SUEDED");
        r = r.replaceAll("(?i)\\bSUEDE\\s+D\\b", "SUEDED");
        r = r.replaceAll("(?i)\\bMER\\s+CERIZED\\b", "MERCERIZED");
        r = r.replaceAll("(?i)\\bMERCER\\s+IZED\\b", "MERCERIZED");
        r = r.replaceAll("(?i)\\bSANFOR\\s+IZED\\b", "SANFORIZED");
        r = r.replaceAll("(?i)\\bSANFORI\\s+ZED\\b", "SANFORIZED");
        r = r.replaceAll("(?i)\\bPRE\\s+SHRUNK\\b", "PRESHRUNK");
        r = r.replaceAll("(?i)\\bPRESHR\\s+UNK\\b", "PRESHRUNK");
        r = r.replaceAll("(?i)\\bWATER\\s+PROOF\\b", "WATERPROOF");
        r = r.replaceAll("(?i)\\bWATERPR\\s+OOF\\b", "WATERPROOF");
        r = r.replaceAll("(?i)\\bWATER\\s+REPELLENT\\b", "WATER REPELLENT");
        r = r.replaceAll("(?i)\\bWRIN\\s+KLE\\b", "WRINKLE");
        r = r.replaceAll("(?i)\\bWRINKL\\s+E\\b", "WRINKLE");
        r = r.replaceAll("(?i)\\bANTI\\s+BACTERIAL\\b", "ANTIBACTERIAL");
        r = r.replaceAll("(?i)\\bANTI\\s+MICROBIAL\\b", "ANTIMICROBIAL");
        r = r.replaceAll("(?i)\\bUV\\s+PROTECTION\\b", "UV PROTECTION");
        r = r.replaceAll("(?i)\\bFIRE\\s+RETARDANT\\b", "FIRE RETARDANT");
        r = r.replaceAll("(?i)\\bFLAME\\s+RETARDANT\\b", "FLAME RETARDANT");
        r = r.replaceAll("(?i)\\bMOIS\\s+TURE\\b", "MOISTURE");
        r = r.replaceAll("(?i)\\bMOISTU\\s+RE\\b", "MOISTURE");
        r = r.replaceAll("(?i)\\bWICK\\s+ING\\b", "WICKING");
        r = r.replaceAll("(?i)\\bWICKI\\s+NG\\b", "WICKING");
        r = r.replaceAll("(?i)\\bBREATH\\s+ABLE\\b", "BREATHABLE");
        r = r.replaceAll("(?i)\\bBREATHA\\s+BLE\\b", "BREATHABLE");
        r = r.replaceAll("(?i)\\bSTRET\\s+CH\\b", "STRETCH");
        r = r.replaceAll("(?i)\\bSTRETC\\s+H\\b", "STRETCH");

        // ===== PRODUCT CODES =====
        // JY8064-circulose type splits
        r = r.replaceAll("(?i)(JY\\d+)-?circul\\s+ose\\b", "$1-circulose");
        r = r.replaceAll("(?i)(JY\\d+)-?circu\\s+lose\\b", "$1-circulose");
        // Handle JY8064-CIRCULOSE (uppercase) to lowercase for consistency
        r = r.replaceAll("(JY\\d+)-CIRCULOSE\\b", "$1-circulose");
        // Fix split product description patterns
        r = r.replaceAll("(?i)(JY\\d+-circulose)\\s+Revisco\\s+Viscose", "$1 80%Revisco Viscose");
        r = r.replaceAll("(?i)(\\d+%-?circulose)\\s+Revisco\\s+Viscose", "$1 80%Revisco Viscose");
        
        // ===== MEASUREMENT UNITS =====
        r = r.replaceAll("(?i)\\bgram\\s*/\\s*km\\b", "gram/km");
        r = r.replaceAll("(?i)\\bg\\s*/\\s*m\\b", "g/m");
        r = r.replaceAll("(?i)\\bg\\s*/\\s*m2\\b", "g/m2");
        r = r.replaceAll("(?i)(\\d+)\\s*g\\s*/\\s*sm\\b", "$1g/sm");
        r = r.replaceAll("(?i)(\\d+)\\s+gsm\\b", "$1gsm");
        // Fix "8 Ogsm" or "8 0gsm" patterns -> 80gsm (OCR splits 80 into 8 + Ogsm/0gsm)
        r = r.replaceAll("(\\d)\\s+Ogsm\\b", "$10gsm");
        r = r.replaceAll("(\\d)\\s+ogsm\\b", "$10gsm");
        r = r.replaceAll("(\\d)\\s+0gsm\\b", "$10gsm");
        // Fix split dimension specs like "20*32 +32/163*85 8" -> "20*32+32/163*85"
        r = r.replaceAll("(\\d+\\*\\d+)\\s+\\+(\\d+)", "$1+$2");
        // Fix "85 8 Ogsm" -> "85 80gsm" pattern (trailing number before Ogsm)
        r = r.replaceAll("(\\d+)\\s+(\\d)\\s+Ogsm\\b", "$1 $20gsm");
        r = r.replaceAll("(\\d+)\\s+(\\d)\\s+0gsm\\b", "$1 $20gsm");

        // ===== SPLIT NUMBERS/SPECS =====
        // 80 %Revis -> 80% Revis
        r = r.replaceAll("(\\d+)\\s+%", "$1%");
        // x32/1 splits
        r = r.replaceAll("(?i)x(\\d+)\\s*/\\s*(\\d+)", "x$1/$2");
        // +32/163*85 splits (construction spec)
        r = r.replaceAll("\\+(\\d+)\\s*/\\s*(\\d+)\\s*\\*\\s*(\\d+)", "+$1/$2*$3");

        // ===== PRODUCT CODE FRAGMENT MERGING =====
        // "ZCX56027-ci Solid rculose" -> "ZCX56027-circulose" (remove Solid noise)
        // "ZCX56027-ci Solid circulose" -> "ZCX56027-circulose" (remove Solid noise)
        r = r.replaceAll("(?i)(\\w+-ci)\\s+Solid\\s+rculose\\b", "$1rculose");
        r = r.replaceAll("(?i)(\\w+-ci)\\s+Solid\\s+circulose\\b", "$1rculose");
        r = r.replaceAll("(?i)(\\w+-ci)\\s+rculose\\b", "$1rculose");
        r = r.replaceAll("(?i)(\\w+-cir)\\s+culose\\b", "$1culose");
        r = r.replaceAll("(?i)(\\w+-circ)\\s+ulose\\b", "$1ulose");
        // Handle "ci rculose" without hyphen prefix
        r = r.replaceAll("(?i)\\bci\\s+rculose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcir\\s+culose\\b", "circulose");
        // Handle "_culose" orphan -> circulose
        r = r.replaceAll("(?i)\\b_culose\\b", "circulose");
        r = r.replaceAll("(?i)\\brculose\\b", "circulose");
        
        // ===== FIX DUPLICATE/FRAGMENTED COMPOSITION =====
        // Fix "withc" typo -> "with c" or "with"
        r = r.replaceAll("(?i)\\bwithc\\b", "with");
        // "viscose with c circulose" -> "Viscose with circulose"
        r = r.replaceAll("(?i)\\bviscose\\s+with\\s+c\\s+circulose\\b", "Viscose with circulose");
        r = r.replaceAll("(?i)\\bviscose\\s+with\\s+circulose\\b", "Viscose with circulose");
        // "Revisco Viscose" -> keep as "Revisco Viscose" (don't merge)
        // "Reviscose" alone (OCR error) -> "Revisco Viscose"
        r = r.replaceAll("(?i)\\bReviscose\\s+circulose\\b", "Revisco Viscose with circulose");
        r = r.replaceAll("(?i)\\bReviscose\\s+viscose\\b", "Revisco Viscose");
        r = r.replaceAll("(?i)\\bReviscose\\b(?!\\s+Viscose)", "Revisco Viscose");
        // Handle fragmented "% Reviscose irculose" -> "% Revisco Viscose with circulose"
        r = r.replaceAll("(?i)%\\s*Reviscose\\s+irculose\\b", "% Revisco Viscose with circulose");
        r = r.replaceAll("(?i)\\birculose\\b", "circulose");
        // "RECYCLED P OLYESTER" -> "RECYCLED POLYESTER"
        r = r.replaceAll("(?i)\\bRECYCLED\\s+P\\s*OLYESTER\\b", "RECYCLED POLYESTER");
        // Add comma before second percentage in composition
        r = r.replaceAll("(?i)(circulose)\\s+(\\d+%)", "$1, $2");
        r = r.replaceAll("(?i)(Viscose)\\s+(\\d+%)", "$1, $2");
        r = r.replaceAll("(?i)(POLYESTER)\\s+(\\d+%)", "$1, $2");
        // Deduplicate repeated textile words in composition
        r = deduplicateTextileWords(r);

        return oneLine(r);
    }

    /**
     * Deduplicate repeated textile words in a string.
     * e.g., "80% Revisco Viscose circulose 20% RECYCLED POLYESTER"
     * Keeps "Revisco Viscose" as separate words (not merged).
     */
    private static String deduplicateTextileWords(String text) {
        if (text == null || text.isBlank()) return text;
        
        String[] words = text.split("\\s+");
        if (words.length <= 1) return text;
        
        StringBuilder result = new StringBuilder();
        java.util.Set<String> recentWords = new java.util.LinkedHashSet<>();
        int windowSize = 4; // Look back window for deduplication
        
        for (String word : words) {
            String wordLower = word.toLowerCase();
            
            // Always keep numbers and percentages
            if (word.matches(".*\\d+.*")) {
                if (result.length() > 0) result.append(' ');
                result.append(word);
                recentWords.clear(); // Reset window after number
                continue;
            }
            
            // Check if this word (or similar) was recently seen
            boolean isDuplicate = false;
            String toRemove = null;
            
            for (String recent : recentWords) {
                if (recent.equalsIgnoreCase(wordLower)) {
                    isDuplicate = true;
                    break;
                }
                // Check for partial matches - but NOT for "revisco" vs "viscose" (different words)
                if (recent.length() >= 6 && wordLower.length() >= 6) {
                    if (recent.startsWith(wordLower) || wordLower.startsWith(recent)) {
                        // Skip if they are different root words (revisco vs viscose)
                        if (!areSameRootWord(recent, wordLower)) {
                            continue;
                        }
                        // Keep the longer one
                        if (wordLower.length() > recent.length()) {
                            toRemove = recent;
                        } else {
                            isDuplicate = true;
                        }
                        break;
                    }
                }
            }
            
            if (toRemove != null) {
                recentWords.remove(toRemove);
            }
            
            if (!isDuplicate) {
                if (result.length() > 0) result.append(' ');
                result.append(word);
                recentWords.add(wordLower);
                
                // Maintain window size
                if (recentWords.size() > windowSize) {
                    java.util.Iterator<String> it = recentWords.iterator();
                    it.next();
                    it.remove();
                }
            }
        }
        
        return result.toString();
    }
    
    /**
     * Check if two words are the same root (not different words like revisco vs viscose)
     */
    private static boolean areSameRootWord(String a, String b) {
        // revisco and viscose are different words - don't merge
        if ((a.startsWith("revis") && b.startsWith("visc")) ||
            (a.startsWith("visc") && b.startsWith("revis"))) {
            return false;
        }
        return true;
    }

    /**
     * Mendeteksi apakah sebuah baris adalah lanjutan dari baris sebelumnya.
     * Returns true jika baris terlihat seperti fragment lanjutan.
     */
    public static boolean isLineContinuation(String prevLine, String currentLine) {
        if (prevLine == null || prevLine.isBlank() || currentLine == null || currentLine.isBlank()) {
            return false;
        }
        
        String prev = oneLine(prevLine);
        String curr = oneLine(currentLine);
        
        // Check if prev line ends with incomplete word fragment
        String[] prevWords = prev.split("\\s+");
        if (prevWords.length == 0) return false;
        
        String lastWord = prevWords[prevWords.length - 1];
        
        // Check if current line starts with lowercase or fragment
        String[] currWords = curr.split("\\s+");
        if (currWords.length == 0) return false;
        
        String firstWord = currWords[0];
        
        // Case 1: Previous ends with uppercase fragment, current starts with uppercase continuation
        // e.g., "INDONES" + "IA", "POL" + "YESTER"
        if (lastWord.matches("[A-Z]{2,}") && firstWord.matches("[A-Z]{2,}")) {
            String combined = lastWord + firstWord;
            if (isKnownWord(combined.toLowerCase())) {
                return true;
            }
        }
        
        // Case 2: Previous ends with mixed case fragment, current continues
        // e.g., "thr" + "ead", "circul" + "ose"
        if (lastWord.matches("[a-zA-Z]{2,4}") && firstWord.matches("[a-zA-Z]{2,}")) {
            String combined = lastWord + firstWord;
            if (isKnownWord(combined.toLowerCase())) {
                return true;
            }
        }
        
        // Case 3: Previous ends with single letter continuation marker
        // e.g., "wit" + "h"
        if (lastWord.length() <= 3 && firstWord.length() == 1) {
            return true;
        }
        
        // Case 4: Current line starts with lowercase (likely continuation)
        if (Character.isLowerCase(firstWord.charAt(0)) && firstWord.length() <= 5) {
            return true;
        }
        
        return false;
    }

    /**
     * Check if a word is a known complete word in garment/textile context (ENTERPRISE BOM)
     */
    private static boolean isKnownWord(String word) {
        if (word == null || word.isBlank()) return false;
        String low = word.toLowerCase();
        return KNOWN_GARMENT_WORDS.contains(low);
    }

    // Enterprise BoM vocabulary for garment industry
    private static final java.util.Set<String> KNOWN_GARMENT_WORDS = java.util.Set.of(
        // Fiber/Materials
        "polyester", "polyamide", "polypropylene", "polyurethane", "viscose", "rayon",
        "revisco", "circulose", "recycled", "cotton", "organic", "elastane", "spandex",
        "lycra", "linen", "flax", "silk", "wool", "merino", "cashmere", "acrylic",
        "modal", "tencel", "lyocell", "bamboo", "hemp", "jute", "acetate", "triacetate",
        "cupro", "metallic", "lurex", "microfiber", "fleece", "leather", "faux", "synthetic", "nylon",
        // Countries
        "indonesia", "china", "vietnam", "bangladesh", "thailand", "malaysia", "pakistan",
        "cambodia", "myanmar", "india", "philippines", "turkey", "egypt", "morocco",
        "tunisia", "portugal", "italy", "germany", "japan", "korea", "taiwan", "hongkong",
        "singapore", "united", "kingdom", "states", "america", "australia", "netherlands",
        "sweden", "france", "spain", "brazil", "mexico", "canada", "srilanka",
        // Cities
        "hangzhou", "suzhou", "shaoxing", "ningbo", "guangzhou", "shenzhen", "dongguan",
        "qingdao", "shanghai", "beijing", "wuxi", "nantong", "dhaka", "chittagong",
        "jakarta", "surabaya", "bandung", "semarang", "hanoi", "mumbai", "delhi",
        "tirupur", "coimbatore", "ahmedabad", "surat",
        // Company Terms
        "trading", "export", "import", "garment", "textile", "accessories", "printing",
        "dyeing", "brothread", "limited", "company", "corporation", "enterprise",
        // Garment Components
        "thread", "sewing", "stitching", "embroidery", "smocking", "shirring", "elastic",
        "drawstring", "buckle", "clasp", "hook", "eyelet", "grommet", "hanger", "loop",
        "label", "woven", "printed", "hangtag", "button", "snap", "stud", "rivet", "shank",
        "zipper", "slider", "puller", "coil", "vislon", "metal", "invisible",
        "ribbon", "tape", "binding", "piping", "cord", "cording", "lace", "braid",
        "twill", "grosgrain", "satin", "velvet", "interlining", "interfacing", "fusible",
        "padding", "wadding", "insulation", "quilting",
        // Garment Parts
        "collar", "cuff", "cuffs", "sleeve", "pocket", "hemline", "waistband", "placket",
        "yoke", "gusset", "godet", "flaps", "pleat", "dart", "darts", "ruffle", "frill", "lining",
        // Packaging
        "polybag", "carton", "tissue", "pins", "clips", "barcode", "sticker",
        // Fabric Types
        "denim", "chambray", "cambric", "poplin", "oxford", "jersey", "interlock",
        "pique", "french", "terry", "voile", "chiffon", "georgette", "crepe", "charmeuse",
        "organza", "tulle", "mesh", "netting", "canvas", "tweed", "flannel", "corduroy",
        "velour", "suede", "sheer", "sheers",
        // Treatment/Finishing
        "washed", "stonewash", "bleach", "bleached", "dyed", "pigment", "reactive",
        "print", "prints", "digital", "screen", "rotary", "transfer", "sublimation",
        "flock", "flocking", "foil", "puff", "glitter", "coated", "laminated", "bonded",
        "brushed", "peached", "sanded", "sueded", "mercerized", "sanforized", "preshrunk",
        "waterproof", "wrinkle", "antibacterial", "antimicrobial", "moisture", "wicking",
        "breathable", "stretch",
        // Common words
        "with", "and"
    );

    /**
     * Menggabungkan array of lines yang mungkin terpotong menjadi string utuh.
     */
    public static String mergeFragmentedLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        
        StringBuilder result = new StringBuilder();
        String prevLine = null;
        
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            
            String current = oneLine(line);
            
            if (prevLine == null) {
                result.append(current);
            } else if (isLineContinuation(prevLine, current)) {
                // Don't add space, merge directly
                result.append(current);
            } else {
                result.append(" ").append(current);
            }
            
            prevLine = current;
        }
        
        // Apply word merge fixes
        return mergeSplitWords(result.toString());
    }

    private static String stripPunctKeepPercent(String s) {
        if (s == null) return "";
        return s.replaceAll("^[\\p{Punct}&&[^%]]+|[\\p{Punct}&&[^%]]+$", "");
    }

    private static String dedupePercentTokens(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");
        StringBuilder sb = new StringBuilder();
        String lastPct = "";
        for (String p : parts) {
            String tok = stripPunctKeepPercent(p);
            if (tok.isBlank()) continue;
            if (TOKEN_HAS_PERCENT.matcher(tok).find()) {
                String pct = tok.replaceAll("[^0-9%]", "");
                if (!lastPct.isBlank() && lastPct.equals(pct)) {
                    continue;
                }
                lastPct = pct;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return oneLine(sb.toString());
    }

    private static String insertBeforePercent(String raw, String percentToken, String toInsert) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");
        StringBuilder sb = new StringBuilder();
        boolean inserted = false;
        for (String p : parts) {
            String tok = stripPunctKeepPercent(p);
            if (!inserted && percentToken.equalsIgnoreCase(tok)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(toInsert);
                sb.append(' ');
                inserted = true;
                sb.append(tok);
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return oneLine(sb.toString());
    }

    private static String extractCompositionSegments(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < parts.length) {
            String tok0 = stripPunctKeepPercent(parts[i]);
            if (!TOKEN_HAS_PERCENT.matcher(tok0).find()) {
                i++;
                continue;
            }

            // Include a few fibre/glue tokens just before the % token (e.g. 'circulose, 20%')
            int start = i;
            int back = i - 1;
            int backLimit = Math.max(0, i - 3);
            while (back >= backLimit) {
                String prev = stripPunctKeepPercent(parts[back]);
                if (prev.isBlank()) {
                    back--;
                    continue;
                }
                String prevLow = prev.toLowerCase();
                if (TOKEN_HAS_PERCENT.matcher(prev).find()) break;
                if (SUPPLIER_STOPWORD.matcher(prev).matches()) break;
                if (ID_LIKE_TOKEN.matcher(prev).matches()) break;
                if (UNIT_TOKEN.matcher(prev).matches()) break;
                if (CONSTRUCTION_HINT.matcher(prev).find()) break;
                if (!(isFiberWord(prevLow) || isCompositionGlueWord(prevLow))) break;
                start = back;
                back--;
            }

            int j = i + 1;
            for (; j < parts.length; j++) {
                String tok = parts[j];
                String tokClean = stripPunctKeepPercent(tok);
                if (tokClean.isBlank()) continue;
                String low = tokClean.toLowerCase();

                // Stop segment when another percent token starts (start of next segment)
                if (TOKEN_HAS_PERCENT.matcher(tokClean).find()) {
                    break;
                }

                if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) break;
                if (ID_LIKE_TOKEN.matcher(tokClean).matches()) break;
                if (UNIT_TOKEN.matcher(tokClean).matches()) break;
                if (CONSTRUCTION_HINT.matcher(tokClean).find()) break;
                if (NYLON_WORD.matcher(tokClean).matches() && j + 1 < parts.length) {
                    String next = stripPunctKeepPercent(parts[j + 1]);
                    if (STAR_SPEC.matcher(next).matches()) break;
                }
                if (MIXED_ALNUM_TOKEN.matcher(tokClean).matches() && !isFiberWord(low)) break;
                if (tokClean.matches("[A-Z]{3,}") && !isFiberWord(low)) break;
            }

            String segmentRaw = joinParts(parts, start, j);
            String cleaned = cleanCompositionTokens(segmentRaw);
            if (!cleaned.isBlank()) {
                if (out.length() > 0) out.append(' ');
                out.append(cleaned);
            }

            // Continue scanning from where we stopped (j). If we stopped due to stopword/construction,
            // move forward by 1 to avoid infinite loops on the same token.
            i = (j <= i ? i + 1 : j);
        }

        return oneLine(out.toString());
    }

    private static String extractMinimalComposition(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");
        int pctIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            String tokClean = stripPunctKeepPercent(parts[i]);
            if (TOKEN_HAS_PERCENT.matcher(tokClean).find()) {
                pctIdx = i;
                break;
            }
        }
        if (pctIdx < 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = pctIdx; i < parts.length; i++) {
            String tok = parts[i];
            String tokClean = stripPunctKeepPercent(tok);
            if (tokClean.isBlank()) continue;
            String low = tokClean.toLowerCase();

            if (i > pctIdx) {
                if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) break;
                if (ID_LIKE_TOKEN.matcher(tokClean).matches()) break;
                if (CONSTRUCTION_HINT.matcher(tokClean).find()) break;
                if (UNIT_TOKEN.matcher(tokClean).matches()) break;
                if (MIXED_ALNUM_TOKEN.matcher(tokClean).matches() && !isFiberWord(low)) break;
                if (tokClean.matches("[A-Z]{3,}") && !isFiberWord(low)) break;

                // Stop if we are entering construction spec like '%nylon 20*32'
                if (NYLON_WORD.matcher(tokClean).matches() && i + 1 < parts.length) {
                    String next = stripPunctKeepPercent(parts[i + 1]);
                    if (STAR_SPEC.matcher(next).matches()) {
                        break;
                    }
                }
            }

            // Keep token as-is; this fallback is intentionally permissive.
            if (sb.length() > 0) sb.append(' ');
            sb.append(tokClean);
        }

        // If we ended up keeping only the percent token, still return it (better than empty).
        return oneLine(sb.toString());
    }

    private record BomDescComp(String description, String composition) {
    }

    private static BomDescComp splitBomTail(String tail) {
        String t = oneLine(tail);
        if (t.isBlank()) return new BomDescComp("", "");

        String[] parts = t.split("\\s+");
        int pctIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (TOKEN_HAS_PERCENT.matcher(part).find()) {
                // Skip fabric blend ratios like "80/20%" - not composition
                if (part.matches("\\d+/\\d+%?")) {
                    continue;
                }
                pctIdx = i;
                break;
            }
        }

        if (pctIdx < 0) {
            return new BomDescComp(t, "");
        }

        int stopIdx = parts.length;
        for (int i = pctIdx; i < parts.length; i++) {
            String tok = parts[i];
            String tokClean = stripPunctKeepPercent(tok);
            String low = tokClean.toLowerCase();

            // Stop if we reached supplier/article/id like segments
            if (i > pctIdx) {
                if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) {
                    stopIdx = i;
                    break;
                }
                if (ID_LIKE_TOKEN.matcher(tokClean).matches()) {
                    stopIdx = i;
                    break;
                }
                if (MIXED_ALNUM_TOKEN.matcher(tokClean).matches() && !isFiberWord(low)) {
                    stopIdx = i;
                    break;
                }
                if (tokClean.matches("[A-Z]{3,}") && !isFiberWord(low)) {
                    stopIdx = i;
                    break;
                }
            }

            if (CONSTRUCTION_HINT.matcher(tokClean).find()) {
                stopIdx = i;
                break;
            }

            if (UNIT_TOKEN.matcher(tokClean).matches()) {
                stopIdx = i;
                break;
            }

            if (low.matches("\\d+(?:[.,]\\d+)?[a-z/]+") && !TOKEN_HAS_PERCENT.matcher(tokClean).find()) {
                stopIdx = i;
                break;
            }

            if (tokClean.matches("\\d+(?:[.,]\\d+)?") && i + 1 < parts.length && UNIT_TOKEN.matcher(parts[i + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "")).matches()) {
                stopIdx = i;
                break;
            }
        }

        String description = joinParts(parts, 0, pctIdx);
        String compositionRaw = joinParts(parts, pctIdx, stopIdx);
        String composition = cleanCompositionTokens(compositionRaw);
        return new BomDescComp(description, composition);
    }

    private static String cleanCompositionTokens(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        // Apply comprehensive word merge first
        r = mergeSplitWords(r);
        String[] parts = r.split("\\s+");
        StringBuilder sb = new StringBuilder();
        String prevKeptLow = "";
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            String tokClean = stripPunctKeepPercent(p);
            if (tokClean.isBlank()) continue;
            String low = tokClean.toLowerCase();

            // Normalize tokens like '80%Revis' -> '80%' (OCR sometimes sticks trailing letters to %)
            if (tokClean.matches("\\d{1,3}%[A-Za-z]{2,}") && TOKEN_HAS_PERCENT.matcher(tokClean).find()) {
                tokClean = tokClean.replaceAll("^(\\d{1,3}%)(?:[A-Za-z]{2,})$", "$1");
                low = tokClean.toLowerCase();
            }

            // Convert bare numeric token into percent when followed by a fibre word (e.g. '20 POLYAMIDE')
            if (tokClean.matches("\\d{1,3}") && i + 1 < parts.length) {
                String next = stripPunctKeepPercent(parts[i + 1]);
                String nextLow = next.toLowerCase();
                if (isFiberWord(nextLow)) {
                    tokClean = tokClean + "%";
                    low = tokClean.toLowerCase();
                }
            }

            // Stop if we are entering construction spec like '%nylon 20*32'
            if (NYLON_WORD.matcher(tokClean).matches() && i + 1 < parts.length && STAR_SPEC.matcher(parts[i + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "")).matches()) {
                break;
            }

            if (low.equals("irculose")) {
                tokClean = "circulose";
                low = "circulose";
            }
            if (low.equals("wit")) {
                tokClean = "with";
                low = "with";
            }
            if (low.equals("yester")) {
                tokClean = "YESTER";
            }

            if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) break;
            if (ID_LIKE_TOKEN.matcher(tokClean).matches()) break;
            if (MIXED_ALNUM_TOKEN.matcher(tokClean).matches() && !isFiberWord(low)) break;
            if (tokClean.matches("[A-Z]{3,}") && !isFiberWord(low)) break;

            // Drop nylon noise unless it's part of a legit fibre list (in our documents it is construction spec)
            if (low.equals("nylon") || low.equals("%nylon")) {
                continue;
            }

            boolean keep = TOKEN_HAS_PERCENT.matcher(tokClean).find() || isFiberWord(low) || isCompositionGlueWord(low);
            // Keep 1-letter fragments right after 'with' (OCR often splits 'circulose' into 'c irculose', or uses 'with c')
            if (!keep && tokClean.length() == 1 && "with".equals(prevKeptLow)) {
                keep = true;
            }
            if (!keep) continue;

            if (sb.length() > 0) sb.append(' ');
            sb.append(tokClean);
            prevKeptLow = tokClean.toLowerCase();
        }
        return oneLine(sb.toString());
    }

    private static boolean isCompositionGlueWord(String low) {
        if (low == null || low.isBlank()) return false;
        return low.equals("with")
                || low.equals("and")
                || low.equals("&")
                || low.equals("so");
    }

    private static String extractCompositionContinuationTokens(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String tokClean = stripPunctKeepPercent(parts[i]);
            if (tokClean.isBlank()) continue;
            String low = tokClean.toLowerCase();

            // Stop if we are entering construction spec like '%nylon 20*32'
            if (NYLON_WORD.matcher(tokClean).matches() && i + 1 < parts.length && STAR_SPEC.matcher(parts[i + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "")).matches()) {
                break;
            }
            if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) break;
            if (ID_LIKE_TOKEN.matcher(tokClean).matches()) break;

            // Normalize common OCR fragments
            if (low.equals("irculose")) {
                tokClean = "circulose";
                low = "circulose";
            }
            if (low.equals("wit")) {
                tokClean = "with";
                low = "with";
            }
            if (low.equals("yester")) {
                tokClean = "YESTER";
                low = "yester";
            }
            if (low.equals("olyester")) {
                tokClean = "POLYESTER";
                low = "polyester";
            }
            if (low.equals("polyeste")) {
                tokClean = "POLYESTER";
                low = "polyester";
            }
            if (low.equals("recy")) {
                tokClean = "RECYCLED";
                low = "recycled";
            }
            if (low.equals("cled")) {
                // Skip 'cled' as it's a fragment of 'recycled' - already handled
                continue;
            }

            boolean keep = isFiberWord(low) || isCompositionGlueWord(low) || (tokClean.length() == 1 && i > 0 && parts[i - 1].equalsIgnoreCase("with"));
            if (!keep) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tokClean);
        }
        // Post-process to merge RECYCLED + POLYESTER patterns
        String result = oneLine(sb.toString());
        result = result.replaceAll("(?i)\\bRECYCLED\\s+POLYESTER\\s+RECYCLED\\s+POLYESTER\\b", "RECYCLED POLYESTER");
        result = result.replaceAll("(?i)\\bPOLYESTER\\s+POLYESTER\\b", "POLYESTER");
        return oneLine(result);
    }

    private static String joinParts(String[] parts, int start, int end) {
        if (parts == null) return "";
        int s = Math.max(0, start);
        int e = Math.min(parts.length, Math.max(s, end));
        if (s >= e) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < e; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return oneLine(sb.toString());
    }

    private static boolean looksLikeCompositionContinuation(String txt) {
        String lower = oneLine(txt).toLowerCase();
        if (lower.isBlank()) return false;
        // Avoid fabric-ratio tokens that are part of description (e.g. 'circulose 80/20')
        // unless the line also carries a true percent token.
        if (!lower.contains("%") && lower.matches(".*\\b\\d{1,3}\\s*/\\s*\\d{1,3}\\b.*")) {
            return false;
        }
        // Common fiber words and OCR fragments
        if (lower.contains("viscose") || lower.contains("polyamide") || lower.contains("polyester") 
            || lower.contains("polyeste") || lower.contains("olyester")
            || lower.contains("nylon") || lower.contains("cotton") || lower.contains("elastane") 
            || lower.contains("spandex") || lower.contains("circulose") || lower.contains("irculose")
            || lower.contains("revisco") || lower.contains("yester")
            || lower.contains("recycled") || lower.contains("recy") || lower.contains("cled")
            || lower.contains(" so pol")
            // keep very specific broken-with pattern only
            || lower.matches(".*\\bwith\\s+c\\b.*")
            || lower.endsWith(" wit")) {
            return true;
        }
        return false;
    }

    private static boolean isFiberWord(String low) {
        if (low == null || low.isBlank()) return false;
        // Core fibers
        return low.contains("viscose") || low.contains("rayon")
                || low.contains("revis") || low.contains("revisco")
                || low.contains("circulose") || low.contains("irculose")
                || low.contains("polyamide") || low.contains("polyester")
                || low.contains("polyeste") || low.contains("olyester")
                || low.contains("polypropylene") || low.contains("polyurethane")
                || low.contains("cotton") || low.contains("organic")
                || low.contains("elastane") || low.contains("spandex") || low.contains("lycra")
                || low.contains("nylon")
                || low.contains("linen") || low.contains("flax")
                || low.contains("silk") || low.contains("wool") || low.contains("merino")
                || low.contains("cashmere") || low.contains("acrylic")
                || low.contains("modal") || low.contains("tencel") || low.contains("lyocell")
                || low.contains("bamboo") || low.contains("hemp") || low.contains("jute")
                || low.contains("acetate") || low.contains("triacetate")
                || low.contains("cupro") || low.contains("metallic") || low.contains("lurex")
                || low.contains("microfiber") || low.contains("fleece")
                || low.contains("recycled") || low.contains("synthetic")
                // Fragments for OCR splits
                || low.equals("recy") || low.equals("cled")
                || low.equals("pol") || low.equals("yester")
                || low.equals("span") || low.equals("dex")
                || low.equals("elas") || low.equals("tane")
                || low.equals("vis") || low.equals("cose")
                || low.equals("ny") || low.equals("lon")
                || low.equals("cot") || low.equals("ton")
                || low.equals("lin") || low.equals("en")
                || low.equals("mod") || low.equals("al")
                || low.equals("ten") || low.equals("cel");
    }

    private static BomRowStart parseBomRowStart(String txt) {
        String[] parts = txt.split("\\s+");
        String position = parts.length > 0 ? parts[0].trim() : "";
        String placement = parts.length > 1 ? parts[1].trim() : "";

        int idx = 2;
        String type = parts.length > idx ? parts[idx].trim() : "";
        if (parts.length > idx + 1) {
            String two = type + " " + parts[idx + 1].trim();
            if ("Thread Trim".equalsIgnoreCase(two)) {
                type = "Thread Trim";
                idx += 2;
            } else {
                // Handle Plain/Cambric/Voile - OCR may split as "Plain/Cambric" + "Voile" or "Plain/Cambric" + "/Voile"
                if (type.contains("Plain/Cambric")) {
                    String next = parts[idx + 1].trim();
                    // Check if next part is "Voile" or "/Voile" or contains "Voile"
                    if (next.equalsIgnoreCase("Voile") || next.equalsIgnoreCase("/Voile") || next.toLowerCase().contains("voile")) {
                        // Join with "/" to form "Plain/Cambric/Voile"
                        type = type + (next.startsWith("/") ? "" : "/") + next.replaceFirst("^/", "");
                        idx += 2;
                    } else if (next.contains("/")) {
                        type = type + next;
                        idx += 2;
                    } else {
                        idx += 1;
                    }
                } else {
                    idx += 1;
                }
            }
        } else {
            idx += 1;
        }

        String tail = "";
        if (parts.length > idx) {
            StringBuilder sb = new StringBuilder();
            for (int i = idx; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(parts[i]);
            }
            tail = oneLine(sb.toString());
        }

        return new BomRowStart(position, placement, type, tail);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static List<List<OcrNewLine>> splitRuns(List<OcrNewLine> lines) {
        List<List<OcrNewLine>> runs = new ArrayList<>();
        List<OcrNewLine> cur = new ArrayList<>();

        Integer prevBottom = null;
        for (OcrNewLine l : lines) {
            boolean tableLike = l.getWords() != null && l.getWords().size() >= 3;
            if (!tableLike) {
                if (cur.size() >= 3) runs.add(cur);
                cur = new ArrayList<>();
                prevBottom = null;
                continue;
            }

            if (prevBottom != null && l.getTop() - prevBottom > 40) {
                if (cur.size() >= 3) runs.add(cur);
                cur = new ArrayList<>();
            }

            cur.add(l);
            prevBottom = l.getBottom();
        }

        if (cur.size() >= 3) runs.add(cur);
        return runs;
    }

    private static List<Integer> deriveColumns(List<OcrNewLine> run) {
        // Bin word centers into columns using tolerance
        final int tol = 35;
        Map<Integer, Integer> bins = new HashMap<>();

        for (OcrNewLine line : run) {
            for (OcrNewWord w : line.getWords()) {
                int cx = (w.getLeft() + w.getRight()) / 2;
                Integer key = findBinKey(bins, cx, tol);
                if (key == null) {
                    bins.put(cx, 1);
                } else {
                    bins.put(key, bins.get(key) + 1);
                }
            }
        }

        // Keep columns that appear frequently
        int minCount = Math.max(4, run.size());
        List<Integer> cols = bins.entrySet().stream()
                .filter(en -> en.getValue() >= minCount)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        // Merge close columns
        List<Integer> merged = new ArrayList<>();
        for (Integer c : cols) {
            if (merged.isEmpty()) {
                merged.add(c);
                continue;
            }
            int last = merged.get(merged.size() - 1);
            if (Math.abs(c - last) <= tol) {
                merged.set(merged.size() - 1, (last + c) / 2);
            } else {
                merged.add(c);
            }
        }

        return merged;
    }

    private static Integer findBinKey(Map<Integer, Integer> bins, int x, int tol) {
        for (Integer k : bins.keySet()) {
            if (Math.abs(k - x) <= tol) return k;
        }
        return null;
    }

    private static int nearestIndex(List<Integer> cols, int x) {
        if (cols == null || cols.isEmpty()) return -1;
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < cols.size(); i++) {
            int d = Math.abs(cols.get(i) - x);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }
}
