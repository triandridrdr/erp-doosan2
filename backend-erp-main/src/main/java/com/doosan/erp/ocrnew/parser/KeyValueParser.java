package com.doosan.erp.ocrnew.parser;

import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.model.OcrNewWord;
import com.doosan.erp.ocrnew.dto.OcrNewKeyValuePairDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyValueParser {

    private static final Pattern COLON_KV = Pattern.compile("^\\s*(.{2,80}?)\\s*[:：]\\s*(.{1,200}?)\\s*$");

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})[\\./-](\\d{1,2})[\\./-](\\d{1,2})\\b");

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
                continue;
            }

            // Fallback: use word bounding boxes to split by large horizontal gap
            OcrNewKeyValuePairDto byWords = tryParseByWordGaps(line);
            if (byWords != null) {
                out.add(byWords);
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
        String raw = safe(s).replaceAll("\\s+", " ").trim();
        if (raw.isBlank()) return raw;

        String canon = canonicalizeSalesOrderKey(raw);
        return canon == null ? raw : canon;
    }

    private static String normalizeValue(String s) {
        String v = safe(s).replaceAll("\\s+", " ").trim();
        if (v.isBlank()) return v;

        // normalize common date formats to ISO-like yyyy-MM-dd (best-effort)
        Matcher m = ISO_DATE.matcher(v);
        if (m.find()) {
            String yyyy = m.group(1);
            String mm = pad2(m.group(2));
            String dd = pad2(m.group(3));
            v = m.replaceFirst(yyyy + "-" + mm + "-" + dd);
        }
        return v;
    }

    private static OcrNewKeyValuePairDto tryParseByWordGaps(OcrNewLine line) {
        if (line.getWords() == null || line.getWords().size() < 4) return null;
        List<OcrNewWord> words = line.getWords().stream()
                .filter(Objects::nonNull)
                .sorted((a, b) -> Integer.compare(a.getLeft(), b.getLeft()))
                .toList();
        if (words.size() < 4) return null;

        int bestIdx = -1;
        int bestGap = 0;
        for (int i = 0; i < words.size() - 1; i++) {
            OcrNewWord w1 = words.get(i);
            OcrNewWord w2 = words.get(i + 1);
            int gap = w2.getLeft() - w1.getRight();
            if (gap > bestGap) {
                bestGap = gap;
                bestIdx = i;
            }
        }

        // Require a meaningful gap; tune for 300DPI render (in pixels)
        if (bestIdx < 0 || bestGap < 40) return null;

        StringBuilder keySb = new StringBuilder();
        for (int i = 0; i <= bestIdx; i++) {
            String t = safe(words.get(i).getText()).trim();
            if (t.isBlank()) continue;
            if (keySb.length() > 0) keySb.append(' ');
            keySb.append(t);
        }
        StringBuilder valSb = new StringBuilder();
        for (int i = bestIdx + 1; i < words.size(); i++) {
            String t = safe(words.get(i).getText()).trim();
            if (t.isBlank()) continue;
            if (valSb.length() > 0) valSb.append(' ');
            valSb.append(t);
        }

        String key = normalizeKey(keySb.toString());
        String val = normalizeValue(valSb.toString());
        if (!looksLikeKey(key) || val.isBlank()) return null;

        return OcrNewKeyValuePairDto.builder()
                .page(line.getPage())
                .key(key)
                .value(val)
                .confidence(line.getConfidence())
                .build();
    }

    private static String canonicalizeSalesOrderKey(String raw) {
        String k = raw.toLowerCase(Locale.ROOT)
                .replace("(", " ")
                .replace(")", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (k.isBlank()) return null;

        if (k.equals("so number") || k.equals("so no") || k.equals("so no.") || k.equals("sales order number") || k.equals("sales order no")) {
            return "SO Number";
        }
        if (k.equals("date") || k.equals("date iso") || k.equals("date iso ")) {
            return "Date (ISO)";
        }
        if (k.equals("season")) {
            return "Season";
        }
        if (k.equals("buyer code") || k.equals("buyer") || k.equals("buyer no") || k.equals("buyer id")) {
            return "Buyer Code";
        }
        if (k.equals("supplier") || k.equals("vendor") || k.equals("seller") || k.equals("supplier name")) {
            return "Supplier";
        }
        if (k.equals("article product no") || k.equals("article product number") || k.equals("article no") || k.equals("product no") || k.equals("product number")) {
            return "Article / Product No";
        }
        if (k.equals("product name") || k.equals("style") || k.equals("style name")) {
            return "Product Name";
        }
        if (k.equals("product type") || k.equals("type") || k.equals("prod type")) {
            return "Product Type";
        }
        if (k.equals("customs customer group") || k.equals("customer group") || k.equals("customs group")) {
            return "Customs Customer Group";
        }
        if (k.equals("type of construction") || k.equals("construction type") || k.equals("construction")) {
            return "Type of Construction";
        }
        return null;
    }

    private static String pad2(String s) {
        String t = safe(s).trim();
        if (t.length() == 1) return "0" + t;
        return t;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ');
    }
}
