package com.doosan.erp.ocrnew.parser;

import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.dto.OcrNewKeyValuePairDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyValueParser {

    private static final Pattern COLON_KV = Pattern.compile("^\\s*(.{2,80}?)\\s*[:：]\\s*(.{1,200}?)\\s*$");

    public List<OcrNewKeyValuePairDto> parseKeyValuePairs(List<OcrNewLine> lines) {
        List<OcrNewKeyValuePairDto> out = new ArrayList<>();
        for (OcrNewLine line : lines) {
            String text = safe(line.getText());
            if (text.isBlank()) continue;

            Matcher m = COLON_KV.matcher(text);
            if (m.find()) {
                String key = normalizeKey(m.group(1));
                String val = normalizeValue(m.group(2));
                if (!key.isBlank() && !val.isBlank()) {
                    out.add(OcrNewKeyValuePairDto.builder()
                            .page(line.getPage())
                            .key(key)
                            .value(val)
                            .confidence(line.getConfidence())
                            .build());
                }
                continue;
            }

            // Fallback: split by long whitespace gap
            String[] parts = text.split("\\s{3,}");
            if (parts.length == 2) {
                String key = normalizeKey(parts[0]);
                String val = normalizeValue(parts[1]);
                if (looksLikeKey(key) && !val.isBlank()) {
                    out.add(OcrNewKeyValuePairDto.builder()
                            .page(line.getPage())
                            .key(key)
                            .value(val)
                            .confidence(line.getConfidence())
                            .build());
                }
            }
        }
        return out;
    }

    public Map<String, String> toFieldMap(List<OcrNewKeyValuePairDto> pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OcrNewKeyValuePairDto p : pairs) {
            if (p.getKey() == null || p.getValue() == null) continue;
            String k = p.getKey().trim();
            String v = p.getValue().trim();
            if (k.isBlank() || v.isBlank()) continue;
            // Keep first occurrence to avoid overwriting by OCR noise
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static boolean looksLikeKey(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2 || t.length() > 80) return false;
        // avoid pure numeric
        if (t.matches("\\d+(?:[.,]\\d+)?")) return false;
        // keys often end with punctuation or contain letters
        return t.matches(".*[A-Za-z].*") || t.endsWith("No") || t.endsWith("No.");
    }

    private static String normalizeKey(String s) {
        return safe(s).replaceAll("\\s+", " ").trim();
    }

    private static String normalizeValue(String s) {
        return safe(s).replaceAll("\\s+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ');
    }
}
