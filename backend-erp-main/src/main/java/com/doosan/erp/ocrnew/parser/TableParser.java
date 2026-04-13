package com.doosan.erp.ocrnew.parser;

import com.doosan.erp.ocrnew.dto.OcrNewBoundingBoxDto;
import com.doosan.erp.ocrnew.dto.OcrNewTableCellDto;
import com.doosan.erp.ocrnew.dto.OcrNewTableDto;
import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.model.OcrNewWord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TableParser {

    private static final Pattern BOM_SECTION_START = Pattern.compile("\\bBill\\s+of\\s+Material\\s*:\\s*Materials\\s+and\\s+Trims\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_SECTION_ANY = Pattern.compile("\\bBill\\s+of\\s+Material\\s*:\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATED_LINE = Pattern.compile("\\bCreated\\s+\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_HEADER_HINT = Pattern.compile("\\bPosition\\b.*\\bPlacement\\b.*\\bType\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOM_ROW_START = Pattern.compile("^(Trim|Shell|Miscellaneous|Material)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_PUNCT = Pattern.compile("^[\\p{Punct}\\s]+$");
    private static final Pattern TOKEN_HAS_PERCENT = Pattern.compile("\\d{1,3}\\s*%");
    private static final Pattern CONSTRUCTION_HINT = Pattern.compile("\\b\\d{2,}x\\d{2,}\\b|\\b\\d{1,3}\\*\\d{1,3}\\b|\\b\\d{2,}\\/\\d{1,2}\\/\\d{1,2}\\b|\\bx\\d{1,3}\\/\\d{1,2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_TOKEN = Pattern.compile("^(km|yd|m|g/m|g/m2|gram/km)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_CAPS_SPILLOVER = Pattern.compile("^[A-Z0-9][A-Z0-9\\s|.\\-]{2,}$");
    private static final Pattern BOM_HEADER_REQUIRED = Pattern.compile("\\bDescription\\b.*\\bComposition\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_LIKE_TOKEN = Pattern.compile("^(QW|QWO|TEL|THD|JY)[A-Z0-9\\-()]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIXED_ALNUM_TOKEN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9\\-()/.]+$");
    private static final Pattern SUPPLIER_STOPWORD = Pattern.compile("^(import|export|ltd|limited|co|company|trading|printing|dyeing|hangzhou|shao?xing|pt|indonesia)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NYLON_WORD = Pattern.compile("^%?nylon$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAR_SPEC = Pattern.compile("^\\d{1,3}\\*\\d{1,3}$");

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

        for (Map.Entry<Integer, List<OcrNewLine>> e : byPage.entrySet()) {
            int page = e.getKey();
            List<OcrNewLine> pageLines = e.getValue().stream()
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
            if (startIdx < 0) continue;

            List<OcrNewLine> section = new ArrayList<>();
            boolean inBody = false;
            OcrNewLine headerLine = null;
            for (int i = startIdx + 1; i < pageLines.size(); i++) {
                OcrNewLine l = pageLines.get(i);
                String txt = oneLine(l.getText());
                if (txt.isBlank()) continue;
                if (BOM_SECTION_ANY.matcher(txt).find() && !BOM_SECTION_START.matcher(txt).find()) break;
                if (CREATED_LINE.matcher(txt).find()) break;
                if (txt.toLowerCase().contains("page") && txt.contains("/")) break;

                if (!inBody) {
                    if (BOM_HEADER_HINT.matcher(txt).find() && BOM_HEADER_REQUIRED.matcher(txt).find()) {
                        inBody = true;
                        headerLine = l;
                    }
                    continue;
                }

                section.add(l);
            }

            List<List<String>> rows = normalizeBomRows(section, headerLine);
            if (rows.size() <= 1) continue;

            List<OcrNewTableCellDto> cells = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
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
                    .page(page)
                    .index(null)
                    .rowCount(rows.size())
                    .columnCount(4)
                    .cells(cells)
                    .rows(rows)
                    .build());
        }

        return out;
    }

    private static List<List<String>> normalizeBomRows(List<OcrNewLine> sectionLines, OcrNewLine headerLine) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Component", "Description", "Category", "Composition"));
        if (sectionLines == null || sectionLines.isEmpty()) return rows;

        List<StringBuilder> rawRowText = new ArrayList<>();
        rawRowText.add(new StringBuilder());

        BomColumnCenters centers = deriveBomColumnCenters(headerLine);
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

            boolean isContinuation = (cells.position.isBlank() || !BOM_ROW_START.matcher(cells.position).find()) && cur != null;
            if (!cells.position.isBlank() && BOM_ROW_START.matcher(cells.position).find()) {
                isContinuation = false;
            }

            if (!isContinuation) {
                cur = new ArrayList<>(List.of(
                        cells.position,
                        cells.description,
                        cells.type,
                        (cells.composition.isBlank() ? compFromWhole : cells.composition)
                ));
                rows.add(cur);
                curRaw = new StringBuilder();
                curRaw.append(wholeLine);
                rawRowText.add(curRaw);
            } else {
                // Append continuation into description/composition based on which column has data
                if (cur == null) {
                    continue;
                }
                if (curRaw != null) {
                    curRaw.append(' ').append(wholeLine);
                }
                if (!cells.description.isBlank()) {
                    cur.set(1, oneLine(cur.get(1) + (cur.get(1).isBlank() ? "" : " ") + cells.description));
                }
                if (!cells.composition.isBlank()) {
                    cur.set(3, normalizeBomComposition(oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + cells.composition)));
                } else if (TOKEN_HAS_PERCENT.matcher(txt).find() || looksLikeCompositionContinuation(txt)) {
                    // if OCR lost column alignment but line clearly looks like composition, append raw text to composition
                    cur.set(3, normalizeBomComposition(oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + txtCleanKeepPercent)));
                } else if (cur.get(3).isBlank() && !compFromWhole.isBlank()) {
                    // last resort: if this line has % but didn't map into composition column, use whole-line extraction
                    cur.set(3, compFromWhole);
                } else if (!cur.get(3).isBlank() && TOKEN_HAS_PERCENT.matcher(cur.get(3)).find() && looksLikeCompositionContinuation(txt)) {
                    // Special: keep fibre continuation words (e.g. 'YESTER') even if the line has no %
                    String cont = extractCompositionContinuationTokens(txtCleanKeepPercent);
                    if (!cont.isBlank()) {
                        cur.set(3, oneLine(cur.get(3) + " " + cont));
                    }
                }
            }
        }

        for (int ri = 1; ri < rows.size() && ri < rawRowText.size(); ri++) {
            List<String> r = rows.get(ri);
            if (r == null || r.size() < 4) continue;
            String mergedRaw = oneLine(rawRowText.get(ri).toString());
            if (!mergedRaw.isBlank() && TOKEN_HAS_PERCENT.matcher(mergedRaw).find()) {
                String comp = normalizeBomComposition(mergedRaw);
                if (!comp.isBlank()) r.set(3, comp);
            }
        }

        return rows;
    }

    private static List<List<String>> normalizeBomRowsFallback(List<OcrNewLine> sectionLines) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Component", "Description", "Category", "Composition"));
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
                cur = new ArrayList<>(List.of(parsed.position, dc.description, parsed.type, dc.composition));
                rows.add(cur);
            } else {
                BomDescComp dc = splitBomTail(txt);
                if (!dc.description.isBlank()) {
                    cur.set(1, oneLine(cur.get(1) + (cur.get(1).isBlank() ? "" : " ") + dc.description));
                }
                if (!dc.composition.isBlank()) {
                    cur.set(3, oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + dc.composition));
                }
            }
        }

        return rows;
    }

    private record BomRowStart(String position, String placement, String type, String tail) {
    }

    private record BomColumnCenters(int xPosition, int xPlacement, int xType, int xDescription, int xComposition) {
        boolean valid() {
            return xPosition > 0 && xPlacement > 0 && xType > 0 && xDescription > 0 && xComposition > 0;
        }
    }

    private record BomLineCells(String position, String placement, String type, String description, String composition) {
    }

    private static BomColumnCenters deriveBomColumnCenters(OcrNewLine headerLine) {
        if (headerLine == null || headerLine.getWords() == null || headerLine.getWords().isEmpty()) {
            return new BomColumnCenters(-1, -1, -1, -1, -1);
        }

        Integer xPosition = null;
        Integer xPlacement = null;
        Integer xType = null;
        Integer xDescription = null;
        Integer xComposition = null;

        for (OcrNewWord w : headerLine.getWords()) {
            String t = oneLine(w.getText());
            if (t.isBlank()) continue;
            int cx = (w.getLeft() + w.getRight()) / 2;

            if (xPosition == null && t.equalsIgnoreCase("Position")) xPosition = cx;
            else if (xPlacement == null && t.equalsIgnoreCase("Placement")) xPlacement = cx;
            else if (xType == null && t.equalsIgnoreCase("Type")) xType = cx;
            else if (xDescription == null && t.equalsIgnoreCase("Description")) xDescription = cx;
            else if (xComposition == null && t.equalsIgnoreCase("Composition")) xComposition = cx;
        }

        return new BomColumnCenters(
                xPosition == null ? -1 : xPosition,
                xPlacement == null ? -1 : xPlacement,
                xType == null ? -1 : xType,
                xDescription == null ? -1 : xDescription,
                xComposition == null ? -1 : xComposition
        );
    }

    private static BomLineCells extractBomLineCells(OcrNewLine line, BomColumnCenters centers) {
        if (line == null || line.getWords() == null || line.getWords().isEmpty()) {
            String t = line == null ? "" : oneLine(line.getText());
            return new BomLineCells("", "", "", t, "");
        }

        StringBuilder position = new StringBuilder();
        StringBuilder placement = new StringBuilder();
        StringBuilder type = new StringBuilder();
        StringBuilder description = new StringBuilder();
        StringBuilder composition = new StringBuilder();

        for (OcrNewWord w : line.getWords()) {
            String t = oneLine(w.getText());
            if (t.isBlank()) continue;
            int cx = (w.getLeft() + w.getRight()) / 2;

            int col = nearestIndex(List.of(
                    centers.xPosition,
                    centers.xPlacement,
                    centers.xType,
                    centers.xDescription,
                    centers.xComposition
            ), cx);

            StringBuilder target;
            if (col == 0) target = position;
            else if (col == 1) target = placement;
            else if (col == 2) target = type;
            else if (col == 3) target = description;
            else target = composition;

            if (target.length() > 0) target.append(' ');
            target.append(t);
        }

        String pos = oneLine(position.toString());
        String plc = oneLine(placement.toString());
        String typ = oneLine(type.toString());
        String desc = oneLine(description.toString());
        String comp = oneLine(composition.toString());

        // Normalize comp first; it may become empty if it was only noise.
        comp = normalizeBomComposition(comp);

        // Fallback 1: if comp became empty but desc contains %, extract from desc.
        if (comp.isBlank() && TOKEN_HAS_PERCENT.matcher(desc).find()) {
            BomDescComp dc = splitBomTail(desc);
            desc = dc.description;
            comp = normalizeBomComposition(dc.composition);
        }

        // Fallback 2: if still empty, try from whole line text (handles column mis-assignment).
        if (comp.isBlank()) {
            String whole = oneLine(line.getText());
            if (TOKEN_HAS_PERCENT.matcher(whole).find()) {
                BomDescComp dc = splitBomTail(whole);
                comp = normalizeBomComposition(dc.composition);
            }
        }

        // trim all-caps spillover from description
        if (!desc.isBlank() && ALL_CAPS_SPILLOVER.matcher(desc).matches() && desc.length() <= 40 && !TOKEN_HAS_PERCENT.matcher(desc).find()) {
            desc = "";
        }

        return new BomLineCells(pos, plc, typ, desc, comp);
    }

    private static String normalizeBomComposition(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";

        // If there's no percent at all, this is almost certainly consumption/weight/supplier noise.
        if (!TOKEN_HAS_PERCENT.matcher(r).find()) {
            return "";
        }

        String seg = extractCompositionSegments(r);
        if (!seg.isBlank()) return seg;

        BomDescComp dc = splitBomTail(r);
        if (!dc.composition.isBlank()) return dc.composition;
        return extractMinimalComposition(r);
    }

    private static String extractCompositionSegments(String raw) {
        String r = oneLine(raw);
        if (r.isBlank()) return "";
        String[] parts = r.split("\\s+");

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < parts.length) {
            String tok0 = parts[i].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (!TOKEN_HAS_PERCENT.matcher(tok0).find()) {
                i++;
                continue;
            }

            int start = i;
            int j = i + 1;
            for (; j < parts.length; j++) {
                String tok = parts[j];
                String tokClean = tok.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
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
                    String next = parts[j + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
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
            String tokClean = parts[i].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (TOKEN_HAS_PERCENT.matcher(tokClean).find()) {
                pctIdx = i;
                break;
            }
        }
        if (pctIdx < 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = pctIdx; i < parts.length; i++) {
            String tok = parts[i];
            String tokClean = tok.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
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
                    String next = parts[i + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
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
            if (TOKEN_HAS_PERCENT.matcher(parts[i]).find()) {
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
            String tokClean = tok.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
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
        String[] parts = r.split("\\s+");
        StringBuilder sb = new StringBuilder();
        String prevKeptLow = "";
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            String tokClean = p.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (tokClean.isBlank()) continue;
            String low = tokClean.toLowerCase();

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
            String tokClean = parts[i].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
            if (tokClean.isBlank()) continue;
            String low = tokClean.toLowerCase();

            // Stop if we are entering construction spec like '%nylon 20*32'
            if (NYLON_WORD.matcher(tokClean).matches() && i + 1 < parts.length && STAR_SPEC.matcher(parts[i + 1].replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "")).matches()) {
                break;
            }
            if (SUPPLIER_STOPWORD.matcher(tokClean).matches()) break;
            if (ID_LIKE_TOKEN.matcher(tokClean).matches()) break;

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

            boolean keep = isFiberWord(low) || isCompositionGlueWord(low) || (tokClean.length() == 1 && i > 0 && parts[i - 1].equalsIgnoreCase("with"));
            if (!keep) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tokClean);
        }
        return oneLine(sb.toString());
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
        if (lower.contains("viscose") || lower.contains("polyamide") || lower.contains("polyester") || lower.contains("nylon") || lower.contains("cotton") || lower.contains("elastane") || lower.contains("spandex") || lower.contains("circulose") || lower.contains("revisco") || lower.contains("yester") || lower.contains(" so pol") || lower.contains(" wit ") || lower.endsWith(" wit")) {
            return true;
        }
        return false;
    }

    private static boolean isFiberWord(String low) {
        if (low == null || low.isBlank()) return false;
        return low.contains("viscose")
                || low.contains("revis")
                || low.contains("revisco")
                || low.contains("circulose")
                || low.contains("polyamide")
                || low.contains("polyester")
                || low.contains("cotton")
                || low.contains("elastane")
                || low.contains("spandex")
                || low.equals("pol")
                || low.equals("yester");
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
                if (type.contains("Plain/Cambric") && parts[idx + 1].contains("/")) {
                    type = type + " " + parts[idx + 1].trim();
                    idx += 2;
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
