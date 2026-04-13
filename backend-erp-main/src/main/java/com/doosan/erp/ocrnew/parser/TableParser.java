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
    private static final Pattern STARTS_WITH_NUMBER = Pattern.compile("^\\d+(?:[.,]\\d+)?\\b");
    private static final Pattern TOKEN_HAS_PERCENT = Pattern.compile("\\d{1,3}\\s*%");
    private static final Pattern CONSTRUCTION_HINT = Pattern.compile("\\b\\d{2,}x\\d{2,}\\b|\\b\\d{1,3}\\*\\d{1,3}\\b|\\b\\d{2,}\\/\\d{1,2}\\/\\d{1,2}\\b|\\bx\\d{1,3}\\/\\d{1,2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_TOKEN = Pattern.compile("^(km|yd|m|g/m|g/m2|gram/km)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_CAPS_SPILLOVER = Pattern.compile("^[A-Z0-9][A-Z0-9\\s|.\\-]{2,}$");
    private static final Pattern BOM_HEADER_REQUIRED = Pattern.compile("\\bDescription\\b.*\\bComposition\\b", Pattern.CASE_INSENSITIVE);

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

        BomColumnCenters centers = deriveBomColumnCenters(headerLine);
        if (!centers.valid()) {
            // fallback to older heuristic if header didn't yield usable columns
            return normalizeBomRowsFallback(sectionLines);
        }

        List<String> cur = null;
        for (OcrNewLine l : sectionLines) {
            String txt = oneLine(l.getText());
            if (txt.isBlank()) continue;
            if (ONLY_PUNCT.matcher(txt).matches()) continue;

            String txtClean = txt.replace("|", " ").replaceAll("[\\p{Punct}]", " ");
            txtClean = oneLine(txtClean);
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

            boolean isContinuation = (cells.position.isBlank() || !BOM_ROW_START.matcher(cells.position).find()) && cur != null;
            if (!cells.position.isBlank() && BOM_ROW_START.matcher(cells.position).find()) {
                isContinuation = false;
            }

            if (!isContinuation) {
                cur = new ArrayList<>(List.of(
                        cells.position,
                        cells.description,
                        cells.type,
                        cells.composition
                ));
                rows.add(cur);
            } else {
                // Append continuation into description/composition based on which column has data
                if (!cells.description.isBlank()) {
                    cur.set(1, oneLine(cur.get(1) + (cur.get(1).isBlank() ? "" : " ") + cells.description));
                }
                if (!cells.composition.isBlank()) {
                    cur.set(3, oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + cells.composition));
                } else if (TOKEN_HAS_PERCENT.matcher(txt).find() || looksLikeCompositionContinuation(txt)) {
                    // if OCR lost column alignment but line clearly looks like composition, append raw text to composition
                    cur.set(3, oneLine(cur.get(3) + (cur.get(3).isBlank() ? "" : " ") + txtClean));
                }
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

        // Post-fix: if composition column got polluted without % but description has %, swap
        if (comp.isBlank() && TOKEN_HAS_PERCENT.matcher(desc).find()) {
            BomDescComp dc = splitBomTail(desc);
            desc = dc.description;
            comp = dc.composition;
        }

        // trim all-caps spillover from description
        if (!desc.isBlank() && ALL_CAPS_SPILLOVER.matcher(desc).matches() && desc.length() <= 40 && !TOKEN_HAS_PERCENT.matcher(desc).find()) {
            desc = "";
        }

        return new BomLineCells(pos, plc, typ, desc, comp);
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
        String composition = joinParts(parts, pctIdx, stopIdx);
        return new BomDescComp(description, composition);
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
        if (lower.contains("viscose") || lower.contains("polyamide") || lower.contains("polyester") || lower.contains("nylon") || lower.contains("cotton") || lower.contains("elastane") || lower.contains("spandex") || lower.contains("circulose")) {
            return true;
        }
        return false;
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
