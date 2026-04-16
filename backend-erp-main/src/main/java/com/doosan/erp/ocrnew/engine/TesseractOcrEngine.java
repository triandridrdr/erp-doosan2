package com.doosan.erp.ocrnew.engine;

import com.doosan.erp.common.constant.ErrorCode;
import com.doosan.erp.common.exception.BusinessException;
import com.doosan.erp.ocrnew.hocr.HocrLine;
import com.doosan.erp.ocrnew.hocr.HocrPage;
import com.doosan.erp.ocrnew.hocr.HocrParser;
import com.doosan.erp.ocrnew.hocr.HocrWord;
import com.doosan.erp.ocrnew.hocr.TextileTextReconstructor;
import com.doosan.erp.ocrnew.model.OcrNewLine;
import com.doosan.erp.ocrnew.model.OcrNewWord;
import com.doosan.erp.ocrnew.parser.TableParser;
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

    // ==================== hOCR Methods ====================

    private final HocrParser hocrParser = new HocrParser();
    private final TextileTextReconstructor textReconstructor = new TextileTextReconstructor();

    /**
     * Generate hOCR HTML from image using Tesseract.
     * hOCR contains bounding box information for better text reconstruction.
     */
    public String generateHocr(BufferedImage image) {
        try {
            Tesseract tesseract = new Tesseract();
            String lang = (language == null || language.isBlank()) ? "eng" : language;
            configureDatapath(tesseract, lang);
            tesseract.setLanguage(lang);
            
            // Enable hOCR output
            tesseract.setTessVariable("tessedit_create_hocr", "1");
            tesseract.setPageSegMode(3); // PSM_AUTO
            tesseract.setTessVariable("preserve_interword_spaces", "1");
            
            return tesseract.doOCR(image);
        } catch (Throwable e) {
            log.error("hOCR generation failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.OCR_PROCESSING_FAILED, e);
        }
    }

    /**
     * Extract lines using hOCR with enhanced fragment merging.
     * This method provides better handling of split words across lines.
     */
    public List<OcrNewLine> extractLinesWithHocr(BufferedImage image, int pageIndex) {
        try {
            String hocrHtml = generateHocr(image);
            List<HocrPage> pages = hocrParser.parseHocr(hocrHtml);
            
            if (pages.isEmpty()) {
                log.warn("No pages found in hOCR, falling back to standard extraction");
                return extractLinesFromImage(image, pageIndex);
            }

            List<OcrNewLine> result = new ArrayList<>();
            int pageNum = pageIndex + 1;

            for (HocrPage page : pages) {
                for (HocrLine hLine : page.getLines()) {
                    List<OcrNewWord> words = new ArrayList<>();
                    
                    for (HocrWord hWord : hLine.getWords()) {
                        words.add(OcrNewWord.builder()
                                .page(pageNum)
                                .text(hWord.getText())
                                .left(hWord.getX())
                                .top(hWord.getY())
                                .right(hWord.getRight())
                                .bottom(hWord.getBottom())
                                .confidence(hWord.getConfidence())
                                .build());
                    }

                    // Merge fragmented words using TextileTextReconstructor
                    String mergedText = textReconstructor.mergeFragmentedWordsInLine(hLine);
                    // Apply TableParser.mergeSplitWords for additional normalization
                    mergedText = TableParser.mergeSplitWords(mergedText);

                    if (!mergedText.isBlank()) {
                        result.add(OcrNewLine.builder()
                                .page(pageNum)
                                .text(mergedText)
                                .left(hLine.getX())
                                .top(hLine.getY())
                                .right(hLine.getRight())
                                .bottom(hLine.getBottom())
                                .confidence(100f) // hOCR doesn't always have line confidence
                                .words(words)
                                .build());
                    }
                }
            }

            log.info("hOCR extracted {} lines from page {}", result.size(), pageNum);
            return result;

        } catch (Exception e) {
            log.warn("hOCR extraction failed, falling back to standard: {}", e.getMessage());
            return extractLinesFromImage(image, pageIndex);
        }
    }

    /**
     * Reconstruct full text from multiple images using hOCR with multi-page handling.
     * This handles text that continues across page boundaries.
     */
    public String reconstructTextFromImages(List<BufferedImage> images) {
        StringBuilder combined = new StringBuilder();
        
        for (int i = 0; i < images.size(); i++) {
            String hocrHtml = generateHocr(images.get(i));
            List<HocrPage> pages = hocrParser.parseHocr(hocrHtml);
            
            String pageText = textReconstructor.reconstructText(pages);
            pageText = TableParser.mergeSplitWords(pageText);
            
            if (combined.length() > 0 && !pageText.isEmpty()) {
                // Check if we need to merge across pages
                String lastLine = getLastLine(combined.toString());
                String firstLine = getFirstLine(pageText);
                
                if (shouldMergeAcrossPages(lastLine, firstLine)) {
                    // Remove last newline and merge
                    while (combined.length() > 0 && 
                           (combined.charAt(combined.length() - 1) == '\n' || 
                            combined.charAt(combined.length() - 1) == ' ')) {
                        combined.deleteCharAt(combined.length() - 1);
                    }
                    combined.append(' ');
                } else {
                    combined.append('\n');
                }
            }
            combined.append(pageText);
        }
        
        return TableParser.mergeSplitWords(combined.toString().trim());
    }

    private String getLastLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int lastNewline = text.lastIndexOf('\n');
        return lastNewline >= 0 ? text.substring(lastNewline + 1) : text;
    }

    private String getFirstLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int firstNewline = text.indexOf('\n');
        return firstNewline >= 0 ? text.substring(0, firstNewline) : text;
    }

    private boolean shouldMergeAcrossPages(String lastLine, String firstLine) {
        if (lastLine == null || lastLine.isEmpty()) return false;
        if (firstLine == null || firstLine.isEmpty()) return false;

        // Check if last line ends without terminal punctuation
        char lastChar = lastLine.trim().charAt(lastLine.trim().length() - 1);
        boolean endsWithTerminal = ".!?;:".indexOf(lastChar) >= 0;

        // Check if first line starts with lowercase
        char firstChar = firstLine.trim().charAt(0);
        boolean startsLowercase = Character.isLowerCase(firstChar);

        // Strong indicator for continuation
        if (!endsWithTerminal && startsLowercase) {
            return true;
        }

        // Check if last line ends with fragment
        String[] lastWords = lastLine.trim().split("\\s+");
        String lastWord = lastWords[lastWords.length - 1];
        if (lastWord.length() <= 3 && !lastWord.matches(".*[aeiouAEIOU]$")) {
            return true;
        }

        return false;
    }
}
