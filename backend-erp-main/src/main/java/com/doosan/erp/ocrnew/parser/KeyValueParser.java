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

    private static final Pattern ORDER_NO_IN_VALUE = Pattern.compile("\\bOrder\\s*No\\s*(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_NO_IN_VALUE = Pattern.compile("\\bProduct\\s*No\\s*(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_OF_ORDER_IN_KEY = Pattern.compile("\\bDate\\s+of\\s+Order\\s+(.{3,40})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPLIER_CODE_IN_KEY = Pattern.compile("\\bSupplier\\s+Code\\s*(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPLIER_NAME_SEASON_IN_KEY = Pattern.compile("\\bSupplier\\s+Name\\s+(.+?)\\s+Season\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_NAME_IN_VALUE = Pattern.compile("\\bProduct\\s+Name\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_TYPE_IN_VALUE = Pattern.compile("\\bProduct\\s+Type\\s+(.+)$", Pattern.CASE_INSENSITIVE);

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

        enrichSalesOrderHeader(out, pairs);
        return out;
    }

    private static void enrichSalesOrderHeader(Map<String, String> out, List<OcrNewKeyValuePairDto> pairs) {
        if (out == null || pairs == null || pairs.isEmpty()) return;

        // 1) From "Order No" row, derive SO Number and Article/Product No (Product No)
        String orderValue = out.get("Order No");
        if (orderValue != null && !orderValue.isBlank()) {
            String so = firstToken(orderValue);
            putIfAbsentNonBlank(out, "SO Number", so);

            Matcher pm = PRODUCT_NO_IN_VALUE.matcher(orderValue);
            if (pm.find()) {
                putIfAbsentNonBlank(out, "Article / Product No", pm.group(1));
            }
        }

        // 2) Some PDFs may put "Order No ..." in value without clean split
        if ((out.get("SO Number") == null || out.get("SO Number").isBlank())) {
            for (OcrNewKeyValuePairDto p : pairs) {
                String v = safe(p.getValue());
                Matcher om = ORDER_NO_IN_VALUE.matcher(v);
                if (om.find()) {
                    putIfAbsentNonBlank(out, "SO Number", om.group(1));
                    break;
                }
            }
        }

        // 3) Date of Order row key contains date; normalize to ISO
        for (OcrNewKeyValuePairDto p : pairs) {
            String key = safe(p.getKey());
            Matcher dm = DATE_OF_ORDER_IN_KEY.matcher(key);
            if (!dm.find()) continue;

            String dateRaw = dm.group(1);
            String dateIso = normalizeDateToIso(dateRaw);
            putIfAbsentNonBlank(out, "Date (ISO)", dateIso);

            String val = safe(p.getValue());
            Matcher pnm = PRODUCT_NAME_IN_VALUE.matcher(val);
            if (pnm.find()) {
                putIfAbsentNonBlank(out, "Product Name", normalizeValue(pnm.group(1)));
            }
            break;
        }

        // 3b) Fallback for hOCR: "Date of Order" as clean key, date in value
        if (out.get("Date (ISO)") == null || out.get("Date (ISO)").isBlank()) {
            String dateOfOrder = out.get("Date of Order");
            if (dateOfOrder != null && !dateOfOrder.isBlank()) {
                String dateIso = normalizeDateToIso(dateOfOrder);
                putIfAbsentNonBlank(out, "Date (ISO)", dateIso);
            }
        }

        // 4) Supplier Code row key has code; map to Buyer Code (best-effort)
        for (OcrNewKeyValuePairDto p : pairs) {
            String key = safe(p.getKey());
            Matcher sm = SUPPLIER_CODE_IN_KEY.matcher(key);
            if (!sm.find()) continue;
            putIfAbsentNonBlank(out, "Buyer Code", sm.group(1));

            String val = safe(p.getValue());
            Matcher pt = PRODUCT_TYPE_IN_VALUE.matcher(val);
            if (pt.find()) {
                putIfAbsentNonBlank(out, "Product Type", normalizeValue(pt.group(1)));
            }
            break;
        }

        // 4b) Fallback for hOCR: "Supplier Code" as clean key, code in value
        if (out.get("Buyer Code") == null || out.get("Buyer Code").isBlank()) {
            String supplierCode = out.get("Supplier Code");
            if (supplierCode != null && !supplierCode.isBlank()) {
                putIfAbsentNonBlank(out, "Buyer Code", supplierCode);
            }
        }

        // 5) Supplier Name + Season row is often merged in key; season value is the pair value
        for (OcrNewKeyValuePairDto p : pairs) {
            String key = safe(p.getKey());
            Matcher nm = SUPPLIER_NAME_SEASON_IN_KEY.matcher(key);
            if (!nm.find()) continue;
            putIfAbsentNonBlank(out, "Supplier", normalizeValue(nm.group(1)));

            String seasonVal = normalizeValue(p.getValue());
            putIfAbsentNonBlank(out, "Season", seasonVal);
            break;
        }

        // 5b) Fallback for hOCR: "Supplier Name" and "Season" as clean keys
        if (out.get("Supplier") == null || out.get("Supplier").isBlank()) {
            String supplierName = out.get("Supplier Name");
            if (supplierName != null && !supplierName.isBlank()) {
                putIfAbsentNonBlank(out, "Supplier", supplierName);
            }
        }
        if (out.get("Season") == null || out.get("Season").isBlank()) {
            // Check for raw key without canonicalization
            for (OcrNewKeyValuePairDto p : pairs) {
                String key = safe(p.getKey()).toLowerCase(Locale.ROOT).trim();
                if (key.equals("season")) {
                    putIfAbsentNonBlank(out, "Season", normalizeValue(p.getValue()));
                    break;
                }
            }
        }

        // 6) Fallback for hOCR: "Product Name" and "Product Type" as clean keys
        if (out.get("Product Name") == null || out.get("Product Name").isBlank()) {
            for (OcrNewKeyValuePairDto p : pairs) {
                String key = safe(p.getKey()).toLowerCase(Locale.ROOT).trim();
                if (key.equals("product name")) {
                    putIfAbsentNonBlank(out, "Product Name", normalizeValue(p.getValue()));
                    break;
                }
            }
        }
        if (out.get("Product Type") == null || out.get("Product Type").isBlank()) {
            for (OcrNewKeyValuePairDto p : pairs) {
                String key = safe(p.getKey()).toLowerCase(Locale.ROOT).trim();
                if (key.equals("product type")) {
                    putIfAbsentNonBlank(out, "Product Type", normalizeValue(p.getValue()));
                    break;
                }
            }
        }

        // 7) Fallback for hOCR: "Customs Customer Group" as clean key
        if (out.get("Customs Customer Group") == null || out.get("Customs Customer Group").isBlank()) {
            for (OcrNewKeyValuePairDto p : pairs) {
                String key = safe(p.getKey()).toLowerCase(Locale.ROOT).trim();
                if (key.equals("customs customer group")) {
                    putIfAbsentNonBlank(out, "Customs Customer Group", normalizeValue(p.getValue()));
                    break;
                }
            }
        }

        // 8) Already canonical keys from parser (keep as-is)
        String ccg = out.get("Customs Customer Group");
        if (ccg != null) putIfAbsentNonBlank(out, "Customs Customer Group", normalizeValue(ccg));
        String toc = out.get("Type of Construction");
        if (toc != null) putIfAbsentNonBlank(out, "Type of Construction", normalizeValue(toc));
    }

    private static void putIfAbsentNonBlank(Map<String, String> out, String key, String value) {
        if (out == null || key == null) return;
        if (out.containsKey(key)) return;
        String v = normalizeValue(value);
        if (v == null || v.isBlank()) return;
        out.put(key, v);
    }

    private static String firstToken(String s) {
        String t = normalizeValue(s);
        if (t.isBlank()) return t;
        String[] parts = t.split("\\s+");
        return parts.length == 0 ? t : parts[0].trim();
    }

    private static String normalizeDateToIso(String raw) {
        String t = normalizeValue(raw);
        if (t.isBlank()) return t;

        // Already ISO-ish?
        Matcher iso = ISO_DATE.matcher(t);
        if (iso.find()) {
            String yyyy = iso.group(1);
            String mm = pad2(iso.group(2));
            String dd = pad2(iso.group(3));
            return yyyy + "-" + mm + "-" + dd;
        }

        // e.g. 14 Nov 2025
        String[] parts = t.split("\\s+");
        if (parts.length >= 3 && parts[0].matches("\\d{1,2}") && parts[2].matches("\\d{4}")) {
            String dd = pad2(parts[0]);
            String mm = monthToNumber(parts[1]);
            String yyyy = parts[2];
            if (mm != null) return yyyy + "-" + mm + "-" + dd;
        }

        return t;
    }

    private static String monthToNumber(String m) {
        if (m == null) return null;
        String k = m.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "jan", "january" -> "01";
            case "feb", "february" -> "02";
            case "mar", "march" -> "03";
            case "apr", "april" -> "04";
            case "may" -> "05";
            case "jun", "june" -> "06";
            case "jul", "july" -> "07";
            case "aug", "august" -> "08";
            case "sep", "sept", "september" -> "09";
            case "oct", "october" -> "10";
            case "nov", "november" -> "11";
            case "dec", "december" -> "12";
            default -> null;
        };
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
