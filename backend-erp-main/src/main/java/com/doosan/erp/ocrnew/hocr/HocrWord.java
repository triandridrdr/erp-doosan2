package com.doosan.erp.ocrnew.hocr;

/**
 * Represents a single word from hOCR output with its bounding box.
 */
public class HocrWord {
    private String text;
    private int x;      // left
    private int y;      // top
    private int width;
    private int height;
    private int pageNum;
    private int lineIndex;
    private float confidence;

    public HocrWord() {}

    public HocrWord(String text, int x, int y, int width, int height, int pageNum, int lineIndex) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.pageNum = pageNum;
        this.lineIndex = lineIndex;
        this.confidence = 100.0f;
    }

    // Right edge of the bounding box
    public int getRight() {
        return x + width;
    }

    // Bottom edge of the bounding box
    public int getBottom() {
        return y + height;
    }

    // Center X
    public int getCenterX() {
        return x + width / 2;
    }

    // Center Y
    public int getCenterY() {
        return y + height / 2;
    }

    // Getters and setters
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }
    public int getLineIndex() { return lineIndex; }
    public void setLineIndex(int lineIndex) { this.lineIndex = lineIndex; }
    public float getConfidence() { return confidence; }
    public void setConfidence(float confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return String.format("HocrWord{text='%s', bbox=[%d,%d,%d,%d], page=%d, line=%d}",
                text, x, y, width, height, pageNum, lineIndex);
    }
}
