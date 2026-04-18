package com.doosan.erp.ocrnew.layout;

import com.doosan.erp.ocrnew.hocr.HocrLine;
import com.doosan.erp.ocrnew.hocr.HocrPage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LayoutAnalyzer {
    public static class AnalyzedLayout {
        public final List<HocrLine> orderedLines;
        public AnalyzedLayout(List<HocrLine> orderedLines) { this.orderedLines = orderedLines; }
    }

    public AnalyzedLayout analyze(List<HocrPage> pages) {
        // Flatten and sort by page, then Y, then X to get reading flow
        List<HocrLine> lines = new ArrayList<>();
        for (HocrPage p : pages) lines.addAll(p.getLines());
        lines.sort(Comparator
                .comparingInt(HocrLine::getPageNum)
                .thenComparingInt(HocrLine::getY)
                .thenComparingInt(HocrLine::getX));
        return new AnalyzedLayout(lines);
    }
}
