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

        for (TableDto t : tables) {
            if (t.getRows() == null || t.getRows().size() < 2) {
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
