package com.doosan.erp.ocrnew.engine;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.BusinessException;
import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.model.OcrNewWord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Word;

import java.awt.image.BufferedImage;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@RequiredArgsConstructor
public class TesseractOcrEngine {

    private final String tessDataPath;
    private final String language;

    public List<OcrNewLine> extractLinesFromImage(BufferedImage image, int pageIndex) {
        try {
            Tesseract tesseract = new Tesseract();
            String lang = (language == null || language.isBlank()) ? "eng" : language;
            configureDatapath(tesseract, lang);
            tesseract.setLanguage(lang);

            List<Word> tessWords = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            return groupWordsIntoLines(tessWords, pageIndex + 1);
        } catch (Throwable e) {
            log.error("OCR-NEW Tesseract failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }
    }

    private void configureDatapath(Tesseract tesseract, String lang) {
        if (tessDataPath != null && !tessDataPath.isBlank()) {
            // User supplied a datapath; keep behavior but validate quickly to avoid native crash.
            Path parentStyle = Path.of(tessDataPath, "tessdata", lang + ".traineddata");
            Path tessdataStyle = Path.of(tessDataPath, lang + ".traineddata");
            if (!Files.exists(parentStyle) && !Files.exists(tessdataStyle)) {
                throw new BusinessException(
                        ErrorCode.OCR_PROCESSING_FAILED,
                        "Missing traineddata for language '" + lang + "' under configured datapath: " + tessDataPath
                );
            }
            tesseract.setDatapath(tessDataPath);
            return;
        }

        // Default: auto-manage tessdata (no OS install). Downloads if missing.
        Path tessdataDir = new TraineddataManager().ensureLanguageData(lang);
        tesseract.setDatapath(tessdataDir.toString());
    }


    private static List<OcrNewLine> groupWordsIntoLines(List<Word> tessWords, int pageNumber) {
        if (tessWords == null || tessWords.isEmpty()) return List.of();

        List<OcrNewWord> words = new ArrayList<>(tessWords.size());
        for (Word w : tessWords) {
            String text = normalizeText(w.getText());
            if (text.isBlank()) continue;
            Rectangle r = w.getBoundingBox();
            if (r == null) continue;
            float conf = (float) w.getConfidence();
            words.add(OcrNewWord.builder()
                    .page(pageNumber)
                    .text(text)
                    .left(r.x)
                    .top(r.y)
                    .right(r.x + r.width)
                    .bottom(r.y + r.height)
                    .confidence(conf)
                    .build());
        }

        words.sort(Comparator
                .comparingInt(OcrNewWord::getTop)
                .thenComparingInt(OcrNewWord::getLeft));

        final int lineTolPx = 12;
        List<List<OcrNewWord>> lineBuckets = new ArrayList<>();

        for (OcrNewWord w : words) {
            if (lineBuckets.isEmpty()) {
                List<OcrNewWord> b = new ArrayList<>();
                b.add(w);
                lineBuckets.add(b);
                continue;
            }

            List<OcrNewWord> last = lineBuckets.get(lineBuckets.size() - 1);
            int lastTop = last.stream().mapToInt(OcrNewWord::getTop).min().orElse(w.getTop());
            if (Math.abs(w.getTop() - lastTop) <= lineTolPx) {
                last.add(w);
            } else {
                List<OcrNewWord> b = new ArrayList<>();
                b.add(w);
                lineBuckets.add(b);
            }
        }

        List<OcrNewLine> out = new ArrayList<>(lineBuckets.size());
        for (List<OcrNewWord> bucket : lineBuckets) {
            bucket.sort(Comparator.comparingInt(OcrNewWord::getLeft));
            String lineText = bucket.stream()
                    .map(OcrNewWord::getText)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b)
                    .trim();
            if (lineText.isBlank()) continue;

            int left = bucket.stream().mapToInt(OcrNewWord::getLeft).min().orElse(0);
            int top = bucket.stream().mapToInt(OcrNewWord::getTop).min().orElse(0);
            int right = bucket.stream().mapToInt(OcrNewWord::getRight).max().orElse(0);
            int bottom = bucket.stream().mapToInt(OcrNewWord::getBottom).max().orElse(0);

            float sum = 0f;
            for (OcrNewWord ww : bucket) sum += ww.getConfidence();
            float avg = bucket.isEmpty() ? 0f : sum / bucket.size();

            out.add(OcrNewLine.builder()
                    .page(pageNumber)
                    .text(lineText)
                    .left(left)
                    .top(top)
                    .right(right)
                    .bottom(bottom)
                    .confidence(avg)
                    .words(bucket)
                    .build());
        }

        return out;
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').trim();
    }
}
