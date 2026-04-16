package com.doosan.erp.ocrnew.hocr;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for hOCR (HTML) output from OCR engines like Tesseract.
 * Extracts words with bounding boxes, handles line/page grouping.
 */
public class HocrParser {
    private static final Logger log = LoggerFactory.getLogger(HocrParser.class);

    // Pattern to parse bbox from title attribute: "bbox x0 y0 x1 y1"
    private static final Pattern BBOX_PATTERN = Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
    
    // Pattern to parse confidence: "x_wconf NN"
    private static final Pattern CONF_PATTERN = Pattern.compile("x_wconf\\s+(\\d+)");
    
    // Pattern to parse page number: "ppageno N"
    private static final Pattern PAGE_PATTERN = Pattern.compile("ppageno\\s+(\\d+)");

    /**
     * Parse hOCR HTML string and extract structured data.
     * 
     * @param hocrHtml The hOCR HTML content
     * @return List of HocrPage objects
     */
    public List<HocrPage> parseHocr(String hocrHtml) {
        List<HocrPage> pages = new ArrayList<>();
        
        if (hocrHtml == null || hocrHtml.isBlank()) {
            return pages;
        }

        try {
            Document doc = Jsoup.parse(hocrHtml);
            
            // Find all pages (ocr_page class)
            Elements pageElements = doc.select(".ocr_page");
            
            if (pageElements.isEmpty()) {
                // Fallback: treat entire document as single page
                HocrPage page = parseSinglePage(doc, 0);
                if (page != null && !page.getLines().isEmpty()) {
                    pages.add(page);
                }
            } else {
                int pageIdx = 0;
                for (Element pageEl : pageElements) {
                    HocrPage page = parsePageElement(pageEl, pageIdx);
                    if (page != null) {
                        pages.add(page);
                    }
                    pageIdx++;
                }
            }
        } catch (Exception e) {
            log.error("Error parsing hOCR: {}", e.getMessage(), e);
        }

        return pages;
    }

    private HocrPage parseSinglePage(Document doc, int pageNum) {
        int[] pageBbox = new int[]{0, 0, 0, 0};
        HocrPage page = new HocrPage(pageNum, 0, 0);
        
        // Find all lines
        Elements lineElements = doc.select(".ocr_line, .ocrx_line");
        int lineIdx = 0;
        
        for (Element lineEl : lineElements) {
            HocrLine line = parseLineElement(lineEl, pageNum, lineIdx);
            if (line != null && !line.getWords().isEmpty()) {
                page.addLine(line);
                lineIdx++;
            }
        }

        // If no lines found, try to find words directly
        if (page.getLines().isEmpty()) {
            Elements wordElements = doc.select(".ocrx_word, .ocr_word");
            if (!wordElements.isEmpty()) {
                HocrLine line = new HocrLine(pageNum, 0);
                for (Element wordEl : wordElements) {
                    HocrWord word = parseWordElement(wordEl, pageNum, 0);
                    if (word != null && !word.getText().isBlank()) {
                        line.addWord(word);
                    }
                }
                if (!line.getWords().isEmpty()) {
                    page.addLine(line);
                }
            }
        }

        return page;
    }

    private HocrPage parsePageElement(Element pageEl, int pageIdx) {
        String title = pageEl.attr("title");
        int[] bbox = parseBbox(title);
        
        int pageNum = pageIdx;
        Matcher pm = PAGE_PATTERN.matcher(title);
        if (pm.find()) {
            pageNum = Integer.parseInt(pm.group(1));
        }

        int width = bbox[2] - bbox[0];
        int height = bbox[3] - bbox[1];
        HocrPage page = new HocrPage(pageNum, width, height);

        // Find all lines within this page
        Elements lineElements = pageEl.select(".ocr_line, .ocrx_line");
        int lineIdx = 0;

        for (Element lineEl : lineElements) {
            HocrLine line = parseLineElement(lineEl, pageNum, lineIdx);
            if (line != null && !line.getWords().isEmpty()) {
                page.addLine(line);
                lineIdx++;
            }
        }

        // Fallback: find paragraphs if no lines
        if (page.getLines().isEmpty()) {
            Elements paraElements = pageEl.select(".ocr_par");
            for (Element paraEl : paraElements) {
                Elements wordsInPara = paraEl.select(".ocrx_word, .ocr_word");
                if (!wordsInPara.isEmpty()) {
                    HocrLine line = new HocrLine(pageNum, lineIdx);
                    for (Element wordEl : wordsInPara) {
                        HocrWord word = parseWordElement(wordEl, pageNum, lineIdx);
                        if (word != null && !word.getText().isBlank()) {
                            line.addWord(word);
                        }
                    }
                    if (!line.getWords().isEmpty()) {
                        page.addLine(line);
                        lineIdx++;
                    }
                }
            }
        }

        return page;
    }

    private HocrLine parseLineElement(Element lineEl, int pageNum, int lineIdx) {
        HocrLine line = new HocrLine(pageNum, lineIdx);
        
        String title = lineEl.attr("title");
        int[] lineBbox = parseBbox(title);
        line.setX(lineBbox[0]);
        line.setY(lineBbox[1]);
        line.setWidth(lineBbox[2] - lineBbox[0]);
        line.setHeight(lineBbox[3] - lineBbox[1]);

        // Find all words within this line
        Elements wordElements = lineEl.select(".ocrx_word, .ocr_word");
        
        if (wordElements.isEmpty()) {
            // No word elements, use line text directly
            String text = lineEl.text().trim();
            if (!text.isEmpty()) {
                HocrWord word = new HocrWord(text, lineBbox[0], lineBbox[1],
                        lineBbox[2] - lineBbox[0], lineBbox[3] - lineBbox[1], pageNum, lineIdx);
                line.addWord(word);
            }
        } else {
            for (Element wordEl : wordElements) {
                HocrWord word = parseWordElement(wordEl, pageNum, lineIdx);
                if (word != null && !word.getText().isBlank()) {
                    line.addWord(word);
                }
            }
        }

        return line;
    }

    private HocrWord parseWordElement(Element wordEl, int pageNum, int lineIdx) {
        String text = wordEl.text().trim();
        if (text.isEmpty()) {
            return null;
        }

        String title = wordEl.attr("title");
        int[] bbox = parseBbox(title);
        
        HocrWord word = new HocrWord(
                text,
                bbox[0],
                bbox[1],
                bbox[2] - bbox[0],
                bbox[3] - bbox[1],
                pageNum,
                lineIdx
        );

        // Parse confidence if available
        Matcher cm = CONF_PATTERN.matcher(title);
        if (cm.find()) {
            word.setConfidence(Float.parseFloat(cm.group(1)));
        }

        return word;
    }

    private int[] parseBbox(String title) {
        int[] bbox = new int[]{0, 0, 0, 0};
        if (title == null || title.isEmpty()) {
            return bbox;
        }

        Matcher m = BBOX_PATTERN.matcher(title);
        if (m.find()) {
            bbox[0] = Integer.parseInt(m.group(1)); // x0 (left)
            bbox[1] = Integer.parseInt(m.group(2)); // y0 (top)
            bbox[2] = Integer.parseInt(m.group(3)); // x1 (right)
            bbox[3] = Integer.parseInt(m.group(4)); // y1 (bottom)
        }
        return bbox;
    }

    /**
     * Get all words from all pages, flattened.
     */
    public List<HocrWord> getAllWords(List<HocrPage> pages) {
        List<HocrWord> allWords = new ArrayList<>();
        for (HocrPage page : pages) {
            allWords.addAll(page.getAllWords());
        }
        return allWords;
    }

    /**
     * Get all lines from all pages, flattened.
     */
    public List<HocrLine> getAllLines(List<HocrPage> pages) {
        List<HocrLine> allLines = new ArrayList<>();
        for (HocrPage page : pages) {
            allLines.addAll(page.getLines());
        }
        return allLines;
    }
}
