package com.doosan.erp.ocr.service;

import com.doosan.erp.ocr.dto.ClassifiedDocumentDto;
import com.doosan.erp.ocr.dto.TableDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OcrDocumentClassifier {

    public ClassifiedDocumentDto classify(Map<String, String> formFields, List<TableDto> tables) {
        Map<String, String> salesOrderHeader = new LinkedHashMap<>();
        Map<String, String> unmapped = new LinkedHashMap<>();

        Map<String, String> normalizedFormFields = normalizeMap(formFields);

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "orderNo", Set.of(
                "order no", "order number", "order no.", "order no:", "so no", "sales order", "po no", "po number"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "dateOfOrder", Set.of(
                "date of order", "order date", "date"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "supplierCode", Set.of(
                "supplier", "supplier code", "vendor code"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "supplierName", Set.of(
                "supplier name", "vendor", "vendor name"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "productNo", Set.of(
                "product no", "product number", "product no."
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "productName", Set.of(
                "product name", "product"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "productType", Set.of(
                "product type", "type"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "productDescription", Set.of(
                "product description", "description"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "season", Set.of(
                "season"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "optionNo", Set.of(
                "option no", "option"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "developmentNo", Set.of(
                "development no", "development number", "product dev no", "product dev no."
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "productDevName", Set.of(
                "product dev name"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "customsCustomerGroup", Set.of(
                "customs customer group"
        ));

        mapHeaderField(normalizedFormFields, unmapped, salesOrderHeader, "typeOfConstruction", Set.of(
                "type of construction", "construction"
        ));

        if (!normalizedFormFields.isEmpty()) {
            for (Map.Entry<String, String> e : normalizedFormFields.entrySet()) {
                unmapped.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        List<Map<String, String>> bomItems = extractBomItems(tables);
        List<Map<String, String>> salesOrderDetails = extractSalesOrderDetails(tables);

        return ClassifiedDocumentDto.builder()
                .salesOrderHeader(salesOrderHeader)
                .salesOrderDetails(salesOrderDetails)
                .bomItems(bomItems)
                .unmappedFields(unmapped)
                .build();
    }

    private void mapHeaderField(
            Map<String, String> normalizedFormFields,
            Map<String, String> unmapped,
            Map<String, String> salesOrderHeader,
            String targetKey,
            Set<String> aliases
    ) {
        String matchedKey = findFirstKey(normalizedFormFields.keySet(), aliases);
        if (matchedKey == null) {
            return;
        }
        String value = normalizedFormFields.get(matchedKey);
        if (value == null) {
            return;
        }
        salesOrderHeader.put(targetKey, value);
        unmapped.remove(matchedKey);
    }

    private String findFirstKey(Set<String> keys, Set<String> aliases) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (String k : keys) {
            for (String a : aliases) {
                if (k.equals(a) || k.contains(a)) {
                    return k;
                }
            }
        }
        return null;
    }

    private Map<String, String> normalizeMap(Map<String, String> input) {
        Map<String, String> out = new LinkedHashMap<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : input.entrySet()) {
            String key = normalizeKey(e.getKey());
            if (key.isBlank()) {
                continue;
            }
            out.put(key, e.getValue() != null ? e.getValue().trim() : "");
        }
        return out;
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        k = k.replace(":", " ").replace("#", " ").replace(".", " ");
        k = k.replaceAll("\\s+", " ");
        return k.trim();
    }

    private List<Map<String, String>> extractBomItems(List<TableDto> tables) {
        List<Map<String, String>> items = new ArrayList<>();
        if (tables == null || tables.isEmpty()) {
            return items;
        }

        for (TableDto t : tables) {
            if (t.getRows() == null || t.getRows().size() < 2) {
                continue;
            }
            List<String> headers = t.getRows().get(0);
            if (!looksLikeBom(headers)) {
                continue;
            }

            List<String> normalizedHeaders = normalizeHeaders(headers);
            for (int r = 1; r < t.getRows().size(); r++) {
                List<String> row = t.getRows().get(r);
                if (row == null || row.stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                for (int c = 0; c < normalizedHeaders.size() && c < row.size(); c++) {
                    String h = normalizedHeaders.get(c);
                    if (h.isBlank()) {
                        continue;
                    }
                    String v = row.get(c);
                    if (v != null && !v.isBlank()) {
                        m.put(h, v.trim());
                    }
                }
                if (!m.isEmpty()) {
                    items.add(m);
                }
            }
        }

        return items;
    }

    private boolean looksLikeBom(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String h : headers) {
            normalized.add(normalizeKey(h));
        }

        boolean hasConsumption = normalized.stream().anyMatch(h -> h.contains("consumption"));
        boolean hasComposition = normalized.stream().anyMatch(h -> h.contains("composition"));
        boolean hasSupplier = normalized.stream().anyMatch(h -> h.contains("supplier"));

        return hasConsumption && (hasComposition || hasSupplier);
    }

    private List<Map<String, String>> extractSalesOrderDetails(List<TableDto> tables) {
        List<Map<String, String>> lines = new ArrayList<>();
        if (tables == null || tables.isEmpty()) {
            return lines;
        }

        Map<String, String> ctx = new LinkedHashMap<>();
        String ctxSectionType = null;

        for (TableDto t : tables) {
            if (t.getRows() == null || t.getRows().size() < 2) {
                continue;
            }

            String country = tryExtractDestinationCountry(t.getRows());
            if (country != null && !country.isBlank()) {
                ctx.put("destinationCountry", country);
                ctx.remove("colourName");
                ctx.remove("hmColourCode");
                ctx.remove("articleNo");
                ctx.remove("ptArticleNumber");
                ctx.remove("optionNo");
                ctx.remove("description");
                ctxSectionType = null;
                continue;
            }

            String sectionType = tryExtractSectionType(t.getRows());
            if (sectionType != null) {
                ctxSectionType = sectionType;
            }

            Map<String, String> headerFields = tryExtractSizeColourHeaderFields(t.getRows());
            if (!headerFields.isEmpty()) {
                ctx.putAll(headerFields);
            }

            if (looksLikeAssortmentTable(t.getRows())) {
                List<Map<String, String>> sectionRows = extractAssortmentRows(t.getRows(), ctxSectionType);
                for (Map<String, String> m : sectionRows) {
                    if (m == null || m.isEmpty()) {
                        continue;
                    }
                    Map<String, String> row = new LinkedHashMap<>(ctx);
                    row.putAll(m);
                    lines.add(row);
                }
                continue;
            }

            List<String> headers = t.getRows().get(0);
            if (!looksLikeSizeQtyTable(headers)) {
                continue;
            }

            List<String> normalizedHeaders = normalizeHeaders(headers);
            for (int r = 1; r < t.getRows().size(); r++) {
                List<String> row = t.getRows().get(r);
                if (row == null || row.stream().allMatch(v -> v == null || v.isBlank())) {
                    continue;
                }
                Map<String, String> m = new LinkedHashMap<>();
                for (int c = 0; c < normalizedHeaders.size() && c < row.size(); c++) {
                    String h = normalizedHeaders.get(c);
                    if (h.isBlank()) {
                        continue;
                    }
                    String v = row.get(c);
                    if (v != null && !v.isBlank()) {
                        m.put(h, v.trim());
                    }
                }
                if (!m.isEmpty()) {
                    lines.add(m);
                }
            }
        }

        return lines;
    }

    private String tryExtractDestinationCountry(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            for (String cell : row) {
                if (cell == null || cell.isBlank()) {
                    continue;
                }
                String s = cell.trim();
                if (s.matches(".*\\b[A-Z]{2}\\b\\s*\\([A-Za-z0-9\u2010\u2011\u2012\u2013\u2014-]+\\).*")) {
                    return s;
                }
            }
        }
        return null;
    }

    private String tryExtractSectionType(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String first = row.get(0);
            String k = normalizeKey(first);
            if (k.equals("assortment")) {
                return "Assortment";
            }
            if (k.equals("solid")) {
                return "Solid";
            }
        }
        return null;
    }

    private Map<String, String> tryExtractSizeColourHeaderFields(List<List<String>> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }

        int hits = 0;
        for (List<String> row : rows) {
            if (row == null || row.size() < 2) {
                continue;
            }
            String keyCell = row.get(0);
            if (keyCell == null || keyCell.isBlank()) {
                continue;
            }
            String key = normalizeKey(keyCell);
            if (!key.endsWith("no") && !key.endsWith("code") && !key.endsWith("name") && !key.contains("description") && !key.contains("article")) {
                continue;
            }

            String value = findLastNonBlank(row);
            if (value == null || value.isBlank()) {
                continue;
            }

            if (key.contains("colour name") || key.contains("color name")) {
                out.put("colourName", value.trim());
                hits++;
                continue;
            }
            if (key.contains("h&m") && key.contains("colour") && key.contains("code")) {
                out.put("hmColourCode", value.trim());
                hits++;
                continue;
            }
            if (key.contains("article") && key.contains("no")) {
                out.put("articleNo", value.trim());
                hits++;
                continue;
            }
            if (key.contains("pt") && key.contains("article") && (key.contains("no") || key.contains("number"))) {
                out.put("ptArticleNumber", value.trim());
                hits++;
                continue;
            }
            if (key.contains("option") && key.contains("no")) {
                out.put("optionNo", value.trim());
                hits++;
                continue;
            }
            if (key.contains("description")) {
                out.put("description", value.trim());
                hits++;
            }
        }

        return hits >= 2 ? out : new LinkedHashMap<>();
    }

    private boolean looksLikeAssortmentTable(List<List<String>> rows) {
        if (rows == null || rows.size() < 2) {
            return false;
        }

        int hits = 0;
        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String first = row.get(0);
            String k = normalizeKey(first);
            if (k.isBlank()) {
                continue;
            }
            if (k.startsWith("xs") || k.equals("s") || k.equals("m") || k.equals("l") || k.startsWith("xl")) {
                hits++;
            }
            if (k.startsWith("quantity")) {
                hits++;
            }
            if (hits >= 3) {
                return true;
            }
        }

        return false;
    }

    private List<Map<String, String>> extractAssortmentRows(List<List<String>> rows, String initialSectionType) {
        List<Map<String, String>> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }

        String currentSectionType = initialSectionType;
        Map<String, String> current = new LinkedHashMap<>();
        if (currentSectionType != null && !currentSectionType.isBlank()) {
            current.put("sectionType", currentSectionType);
        }

        for (List<String> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String first = row.get(0);
            String k = normalizeKey(first);
            if (k.isBlank()) {
                continue;
            }

            if (k.equals("assortment") || k.equals("solid")) {
                if (hasSizeOrQty(current)) {
                    out.add(current);
                }
                current = new LinkedHashMap<>();
                currentSectionType = k.equals("assortment") ? "Assortment" : "Solid";
                current.put("sectionType", currentSectionType);
                continue;
            }

            String last = findLastNonBlank(row);
            if (last == null || last.isBlank()) {
                continue;
            }

            String size = normalizeAssortmentSizeKey(k);
            if (size != null) {
                current.put(size, last.trim());
                continue;
            }

            if (k.startsWith("quantity")) {
                current.put("quantity", last.trim());
            }
        }

        if (hasSizeOrQty(current)) {
            out.add(current);
        }

        return out;
    }

    private boolean hasSizeOrQty(Map<String, String> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        return row.containsKey("XS") || row.containsKey("S") || row.containsKey("M") || row.containsKey("L") || row.containsKey("XL") || row.containsKey("quantity");
    }

    private String normalizeAssortmentSizeKey(String normalizedKey) {
        if (normalizedKey == null) {
            return null;
        }
        String k = normalizedKey.trim();
        if (k.startsWith("xs")) {
            return "XS";
        }
        if (k.equals("s") || k.startsWith("s ") || k.startsWith("s(")) {
            return "S";
        }
        if (k.equals("m") || k.startsWith("m ") || k.startsWith("m(")) {
            return "M";
        }
        if (k.equals("l") || k.startsWith("l ") || k.startsWith("l(")) {
            return "L";
        }
        if (k.startsWith("xl")) {
            return "XL";
        }
        return null;
    }

    private String findLastNonBlank(List<String> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        for (int i = row.size() - 1; i >= 0; i--) {
            String v = row.get(i);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private boolean looksLikeSizeQtyTable(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String h : headers) {
            normalized.add(normalizeKey(h));
        }

        boolean hasSize = normalized.stream().anyMatch(h -> h.contains("size"));
        boolean hasQty = normalized.stream().anyMatch(h -> h.contains("qty") || h.contains("quantity") || h.equals("q'ty"));

        return hasSize && hasQty;
    }

    private List<String> normalizeHeaders(List<String> headers) {
        List<String> out = new ArrayList<>();
        if (headers == null) {
            return out;
        }
        for (String h : headers) {
            out.add(normalizeKey(h));
        }
        return out;
    }
}
