package com.doosan.erp.ocrnew.text;

import com.doosan.erp.ocrnew.hocr.HocrLine;

import java.util.ArrayList;
import java.util.List;

public class TextReconstructionEngine {
    public static class ReconstructedText {
        public final List<String> lines;
        public ReconstructedText(List<String> lines) { this.lines = lines; }
        public String join() { return String.join("\n", lines); }
    }

    public ReconstructedText reconstruct(List<HocrLine> orderedLines, int maxVerticalGapPx, int maxLeftAlignDiffPx) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        HocrLine prev = null;
        for (HocrLine line : orderedLines) {
            String txt = mergeBrokenWords(line.getText());
            if (prev == null) {
                cur.append(txt);
            } else if (line.isContinuationOf(prev, maxVerticalGapPx, maxLeftAlignDiffPx)) {
                String merged = mergeNumericAndUnits(cur.toString(), txt);
                cur.setLength(0);
                cur.append(merged);
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(txt);
            }
            prev = line;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return new ReconstructedText(out);
    }

    public String mergeBrokenWords(String s) {
        if (s == null || s.isBlank()) return "";
        String r = s;
        r = r.replaceAll("(?i)\\bci\\s*rculose\\b", "circulose");
        r = r.replaceAll("(?i)\\br\\s*culose\\b", "rculose");
        r = r.replaceAll("(?i)\\brecy\\s*cled\\b", "recycled");
        r = r.replaceAll("(?i)\\breviscose\\b", "Reviscose");
        r = r.replaceAll("(?i)\\brevisco\\s*se\\b", "Reviscose");
        r = r.replaceAll("\u00A0", " ");
        r = r.replaceAll("\u200B|\u200C|\u200D|\u2060", "");
        r = r.replaceAll("\s{2,}", " ").trim();
        return r;
    }

    public String mergeNumericAndUnits(String left, String right) {
        String l = left.replaceAll("\s+$", "");
        String r = right.replaceAll("^\s+", "");
        // 150 + x94 -> 150x94
        if (l.matches(".*\\d$") && r.matches("^[xX]\\d+.*")) {
            return l + r;
        }
        // 75g + /sm -> 75g/sm
        if (l.matches(".*[A-Za-z]$") && r.startsWith("/")) {
            return l + r;
        }
        // 55" + CW -> 55"CW
        if (l.endsWith("\"") && r.matches("^[A-Za-z].*")) {
            return l + r;
        }
        return l + " " + r;
    }
}
