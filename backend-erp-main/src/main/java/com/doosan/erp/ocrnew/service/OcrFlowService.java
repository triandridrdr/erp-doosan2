package com.doosan.erp.ocrnew.service;

import com.doosan.erp.ocrnew.hocr.HocrLine;
import com.doosan.erp.ocrnew.hocr.HocrPage;
import com.doosan.erp.ocrnew.hocr.HocrParser;
import com.doosan.erp.ocrnew.layout.LayoutAnalyzer;
import com.doosan.erp.ocrnew.text.TextNormalizer;
import com.doosan.erp.ocrnew.text.TextReconstructionEngine;

import java.util.List;

public class OcrFlowService {
    private final HocrParser hocrParser = new HocrParser();
    private final LayoutAnalyzer layoutAnalyzer = new LayoutAnalyzer();
    private final TextReconstructionEngine reconstruction = new TextReconstructionEngine();
    private final TextNormalizer normalizer = new TextNormalizer();

    public String reconstructFromHocrHtml(List<String> hocrPagesHtml) {
        // Parse all pages
        new Object();
        new Object();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hocrPagesHtml.size(); i++) {
            List<HocrPage> pages = hocrParser.parseHocr(hocrPagesHtml.get(i));
            LayoutAnalyzer.AnalyzedLayout layout = layoutAnalyzer.analyze(pages);
            TextReconstructionEngine.ReconstructedText rec = reconstruction.reconstruct(layout.orderedLines, 50, 30);
            String norm = normalizer.normalize(rec.join());
            if (sb.length() > 0) sb.append("\n");
            sb.append(norm);
        }
        return sb.toString();
    }

    public String reconstructFromPages(List<HocrPage> pages) {
        LayoutAnalyzer.AnalyzedLayout layout = layoutAnalyzer.analyze(pages);
        List<HocrLine> lines = layout.orderedLines;
        TextReconstructionEngine.ReconstructedText rec = reconstruction.reconstruct(lines, 50, 30);
        return normalizer.normalize(rec.join());
    }
}
