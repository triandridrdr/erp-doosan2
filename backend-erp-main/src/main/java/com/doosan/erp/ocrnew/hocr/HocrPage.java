package com.doosan.erp.ocrnew.hocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single page from hOCR output, containing multiple lines.
 */
public class HocrPage {
    private int pageNum;
    private int width;
    private int height;
    private List<HocrLine> lines;

    public HocrPage() {
        this.lines = new ArrayList<>();
    }

    public HocrPage(int pageNum, int width, int height) {
        this.pageNum = pageNum;
        this.width = width;
        this.height = height;
        this.lines = new ArrayList<>();
    }

    public void addLine(HocrLine line) {
        lines.add(line);
    }

    /**
     * Get all text from this page.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).getText());
        }
        return sb.toString();
    }

    /**
     * Get all words from this page, flattened.
     */
    public List<HocrWord> getAllWords() {
        List<HocrWord> allWords = new ArrayList<>();
        for (HocrLine line : lines) {
            allWords.addAll(line.getWords());
        }
        return allWords;
    }

    // Getters and setters
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public List<HocrLine> getLines() { return lines; }
    public void setLines(List<HocrLine> lines) { this.lines = lines; }

    @Override
    public String toString() {
        return String.format("HocrPage{num=%d, size=%dx%d, lines=%d}", 
                pageNum, width, height, lines.size());
    }
}
