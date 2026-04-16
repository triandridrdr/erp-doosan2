package com.doosan.erp.ocrnew.hocr;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a line of text from hOCR output, containing multiple words.
 */
public class HocrLine {
    private List<HocrWord> words;
    private int x;      // left edge of line bbox
    private int y;      // top edge
    private int width;
    private int height;
    private int pageNum;
    private int lineIndex;

    public HocrLine() {
        this.words = new ArrayList<>();
    }

    public HocrLine(int pageNum, int lineIndex) {
        this.words = new ArrayList<>();
        this.pageNum = pageNum;
        this.lineIndex = lineIndex;
    }

    public void addWord(HocrWord word) {
        words.add(word);
        recalculateBbox();
    }

    private void recalculateBbox() {
        if (words.isEmpty()) return;
        
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (HocrWord w : words) {
            minX = Math.min(minX, w.getX());
            minY = Math.min(minY, w.getY());
            maxX = Math.max(maxX, w.getRight());
            maxY = Math.max(maxY, w.getBottom());
        }

        this.x = minX;
        this.y = minY;
        this.width = maxX - minX;
        this.height = maxY - minY;
    }

    /**
     * Get the full text of this line by joining all words.
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words.get(i).getText());
        }
        return sb.toString();
    }

    /**
     * Check if this line likely continues from the previous line.
     * @param prevLine The previous line
     * @param maxVerticalGap Maximum vertical gap in pixels
     * @param maxLeftAlignDiff Maximum difference in left alignment
     */
    public boolean isContinuationOf(HocrLine prevLine, int maxVerticalGap, int maxLeftAlignDiff) {
        if (prevLine == null || this.words.isEmpty() || prevLine.words.isEmpty()) {
            return false;
        }

        // Must be on same page or consecutive pages
        if (Math.abs(this.pageNum - prevLine.pageNum) > 1) {
            return false;
        }

        // Check vertical gap
        int verticalGap = this.y - prevLine.getBottom();
        if (this.pageNum == prevLine.pageNum && verticalGap > maxVerticalGap) {
            return false;
        }

        // Check left alignment (for same-page lines)
        if (this.pageNum == prevLine.pageNum) {
            int alignDiff = Math.abs(this.x - prevLine.x);
            if (alignDiff > maxLeftAlignDiff) {
                return false;
            }
        }

        // Check if previous line ends without punctuation
        String prevText = prevLine.getText().trim();
        if (prevText.isEmpty()) return false;
        
        char lastChar = prevText.charAt(prevText.length() - 1);
        boolean endsWithPunct = ".!?;:".indexOf(lastChar) >= 0;
        
        // Check if this line starts with lowercase
        String thisText = this.getText().trim();
        if (thisText.isEmpty()) return false;
        
        char firstChar = thisText.charAt(0);
        boolean startsLowercase = Character.isLowerCase(firstChar);

        // Heuristics for continuation
        if (!endsWithPunct && startsLowercase) {
            return true;
        }

        // Check if previous line ends with word fragment (short word without vowel pattern)
        String lastWord = getLastWord(prevText);
        if (lastWord != null && looksLikeFragment(lastWord)) {
            return true;
        }

        return false;
    }

    private String getLastWord(String text) {
        String[] parts = text.trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    private boolean looksLikeFragment(String word) {
        if (word == null || word.length() < 2) return false;
        // Short words without ending vowel or common suffix
        if (word.length() <= 3 && !word.matches(".*[aeiouAEIOU]$")) {
            return true;
        }
        // Ends with hyphen
        if (word.endsWith("-")) {
            return true;
        }
        return false;
    }

    // Right edge
    public int getRight() {
        return x + width;
    }

    // Bottom edge
    public int getBottom() {
        return y + height;
    }

    // Getters and setters
    public List<HocrWord> getWords() { return words; }
    public void setWords(List<HocrWord> words) { this.words = words; recalculateBbox(); }
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

    @Override
    public String toString() {
        return String.format("HocrLine{text='%s', bbox=[%d,%d,%d,%d], page=%d, words=%d}",
                getText(), x, y, width, height, pageNum, words.size());
    }
}
