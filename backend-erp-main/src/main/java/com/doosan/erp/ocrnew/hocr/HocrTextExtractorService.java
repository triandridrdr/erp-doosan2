package com.doosan.erp.ocrnew.hocr;

import com.doosan.erp.ocrnew.parser.TableParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for extracting structured fields (Composition, Description) from hOCR output.
 * Handles multi-page documents, fragmented text, and textile-specific normalization.
 * 
 * Note: This is a utility class, not a Spring bean. 
 * Main hOCR functionality is integrated into TesseractOcrEngine.
 */
public class HocrTextExtractorService {
    private static final Logger log = LoggerFactory.getLogger(HocrTextExtractorService.class);

    private final HocrParser hocrParser;
    private final TextileTextReconstructor textReconstructor;

    // Field detection patterns
    private static final Pattern COMPOSITION_HEADER = Pattern.compile(
            "(?i)\\b(composition|material\\s*composition|fabric\\s*composition)\\s*:?"
    );
    private static final Pattern DESCRIPTION_HEADER = Pattern.compile(
            "(?i)\\b(description|item\\s*description|product\\s*description|fabric\\s*description)\\s*:?"
    );
    
    // Pattern for composition content (percentage + fiber)
    private static final Pattern COMPOSITION_CONTENT = Pattern.compile(
            "(?i)(\\d{1,3})\\s*[%/]?\\s*(\\d{1,3})?\\s*%?\\s*" +
            "(cotton|polyester|viscose|nylon|elastane|spandex|lycra|wool|silk|linen|" +
            "rayon|modal|tencel|lyocell|bamboo|acrylic|polyamide|circulose|recycled)"
    );
    
    // Pattern for description content (dimensions, weight, etc.)
    private static final Pattern DESCRIPTION_CONTENT = Pattern.compile(
            "(?i)(\\d+)\\s*[xX×]\\s*(\\d+)|" +           // dimensions like 150x94
            "(\\d+)\\s*g\\s*/\\s*(sm|m2)|" +             // weight like 75g/sm
            "(\\d+)\\s*gsm|" +                           // weight like 80gsm
            "(\\d+)\\s*[dD]\\s*[xX×]?\\s*(\\d+)?\\s*[sS]|" + // denier like 20dx45s
            "\\d+'\\s*(CW|cw|SW|sw)"                     // width like 55'CW
    );

    public HocrTextExtractorService() {
        this.hocrParser = new HocrParser();
        this.textReconstructor = new TextileTextReconstructor();
    }

    /**
     * Extract Composition and Description from hOCR HTML.
     * 
     * @param hocrHtml The hOCR HTML content
     * @return Map with "composition" and "description" keys
     */
    public Map<String, String> extractFields(String hocrHtml) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("composition", "");
        result.put("description", "");

        if (hocrHtml == null || hocrHtml.isBlank()) {
            return result;
        }

        try {
            // Parse hOCR
            List<HocrPage> pages = hocrParser.parseHocr(hocrHtml);
            if (pages.isEmpty()) {
                log.warn("No pages found in hOCR");
                return result;
            }

            // Get all lines across all pages
            List<HocrLine> allLines = hocrParser.getAllLines(pages);
            
            // Reconstruct full text with fragment merging
            String fullText = textReconstructor.reconstructText(pages);
            log.debug("Reconstructed text length: {}", fullText.length());

            // Extract composition
            String composition = extractComposition(allLines, fullText);
            result.put("composition", composition);

            // Extract description
            String description = extractDescription(allLines, fullText);
            result.put("description", description);

            log.info("Extracted - Composition: '{}', Description: '{}'", 
                    truncate(composition, 50), truncate(description, 50));

        } catch (Exception e) {
            log.error("Error extracting fields from hOCR: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * Extract composition field from lines and full text.
     */
    private String extractComposition(List<HocrLine> lines, String fullText) {
        StringBuilder composition = new StringBuilder();

        // Strategy 1: Look for explicit "Composition:" header
        int compositionStartIdx = findHeaderIndex(lines, COMPOSITION_HEADER);
        if (compositionStartIdx >= 0) {
            composition.append(extractFieldContent(lines, compositionStartIdx, DESCRIPTION_HEADER));
        }

        // Strategy 2: Look for percentage + fiber patterns in full text
        if (composition.length() == 0) {
            Matcher m = COMPOSITION_CONTENT.matcher(fullText);
            Set<String> found = new LinkedHashSet<>();
            while (m.find()) {
                found.add(m.group().trim());
            }
            if (!found.isEmpty()) {
                composition.append(String.join(" ", found));
            }
        }

        // Strategy 3: Look for lines containing percentage patterns
        if (composition.length() == 0) {
            for (HocrLine line : lines) {
                String text = line.getText();
                if (text.matches(".*\\d+\\s*[%/].*") && COMPOSITION_CONTENT.matcher(text).find()) {
                    if (composition.length() > 0) composition.append(" ");
                    composition.append(text);
                }
            }
        }

        // Normalize and clean
        String result = composition.toString().trim();
        result = textReconstructor.normalizeTextileText(result);
        result = TableParser.mergeSplitWords(result);
        
        return cleanupField(result);
    }

    /**
     * Extract description field from lines and full text.
     */
    private String extractDescription(List<HocrLine> lines, String fullText) {
        StringBuilder description = new StringBuilder();

        // Strategy 1: Look for explicit "Description:" header
        int descriptionStartIdx = findHeaderIndex(lines, DESCRIPTION_HEADER);
        if (descriptionStartIdx >= 0) {
            description.append(extractFieldContent(lines, descriptionStartIdx, COMPOSITION_HEADER));
        }

        // Strategy 2: Look for description content patterns
        if (description.length() == 0) {
            Matcher m = DESCRIPTION_CONTENT.matcher(fullText);
            List<String> found = new ArrayList<>();
            int lastEnd = -1;
            while (m.find()) {
                // Include context around the match
                int start = Math.max(0, m.start() - 20);
                int end = Math.min(fullText.length(), m.end() + 20);
                
                // Avoid overlapping
                if (start > lastEnd) {
                    String context = fullText.substring(start, end).trim();
                    found.add(context);
                    lastEnd = end;
                }
            }
            if (!found.isEmpty()) {
                description.append(String.join(" ", found));
            }
        }

        // Strategy 3: Look for lines with dimension/weight patterns
        if (description.length() == 0) {
            for (HocrLine line : lines) {
                String text = line.getText();
                if (DESCRIPTION_CONTENT.matcher(text).find()) {
                    if (description.length() > 0) description.append(" ");
                    description.append(text);
                }
            }
        }

        // Normalize and clean
        String result = description.toString().trim();
        result = textReconstructor.normalizeTextileText(result);
        result = TableParser.mergeSplitWords(result);
        
        return cleanupField(result);
    }

    /**
     * Find the line index containing a header pattern.
     */
    private int findHeaderIndex(List<HocrLine> lines, Pattern headerPattern) {
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).getText();
            if (headerPattern.matcher(text).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract field content starting from headerIdx until another header or end.
     */
    private String extractFieldContent(List<HocrLine> lines, int headerIdx, Pattern stopPattern) {
        StringBuilder content = new StringBuilder();
        
        // Get content from header line (after the header text)
        String headerLine = lines.get(headerIdx).getText();
        Matcher m = Pattern.compile("(?i)(composition|description)\\s*:?\\s*(.*)").matcher(headerLine);
        if (m.find() && m.group(2) != null && !m.group(2).isBlank()) {
            content.append(m.group(2).trim());
        }

        // Continue collecting from subsequent lines
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            HocrLine line = lines.get(i);
            String text = line.getText().trim();
            
            if (text.isEmpty()) continue;
            
            // Stop if we hit another header
            if (stopPattern.matcher(text).find()) {
                break;
            }
            
            // Stop if we hit a new section (all caps, very short line, etc.)
            if (looksLikeNewSection(text)) {
                break;
            }

            // Check if this is a continuation
            if (content.length() > 0) {
                // Check if we should merge with previous (fragmented word)
                String lastWord = getLastWord(content.toString());
                String firstWord = getFirstWord(text);
                
                if (shouldMergeAcrossLines(lastWord, firstWord)) {
                    // Remove trailing space and merge
                    while (content.length() > 0 && content.charAt(content.length() - 1) == ' ') {
                        content.deleteCharAt(content.length() - 1);
                    }
                    content.append(text);
                } else {
                    content.append(" ").append(text);
                }
            } else {
                content.append(text);
            }
        }

        return content.toString().trim();
    }

    private boolean looksLikeNewSection(String text) {
        if (text == null || text.length() < 3) return false;
        
        // All caps and short
        if (text.equals(text.toUpperCase()) && text.length() < 20 && !text.matches(".*\\d.*")) {
            return true;
        }
        
        // Starts with common section headers
        String lower = text.toLowerCase();
        if (lower.startsWith("quantity") || lower.startsWith("price") || 
            lower.startsWith("total") || lower.startsWith("unit") ||
            lower.startsWith("supplier") || lower.startsWith("vendor")) {
            return true;
        }
        
        return false;
    }

    private boolean shouldMergeAcrossLines(String lastWord, String firstWord) {
        if (lastWord == null || firstWord == null) return false;
        if (lastWord.isEmpty() || firstWord.isEmpty()) return false;

        // Last word ends with hyphen
        if (lastWord.endsWith("-")) return true;

        // First word starts with lowercase (continuation)
        if (Character.isLowerCase(firstWord.charAt(0))) {
            // Check if it's a known fragment
            String lower = firstWord.toLowerCase();
            if (lower.matches("(r?c?u?l?ose|ester|cose|ylon|tane|dex|ton|en|ed|ing|tion|ment).*")) {
                return true;
            }
        }

        // Last word is very short (likely fragment)
        if (lastWord.length() <= 2 && Character.isLetter(lastWord.charAt(0))) {
            return true;
        }

        return false;
    }

    private String getLastWord(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.trim().split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "";
    }

    private String getFirstWord(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.trim().split("\\s+");
        return words.length > 0 ? words[0] : "";
    }

    private String cleanupField(String text) {
        if (text == null) return "";
        
        String r = text;
        
        // Remove duplicate spaces
        r = r.replaceAll("\\s+", " ");
        
        // Remove leading/trailing punctuation noise
        r = r.replaceAll("^[\\s,;:]+", "");
        r = r.replaceAll("[\\s,;:]+$", "");
        
        // Fix common OCR artifacts
        r = r.replaceAll("\\|", "l");
        r = r.replaceAll("0(?=[a-zA-Z])", "O");
        r = r.replaceAll("(?<=[a-zA-Z])0", "o");
        
        return r.trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Process hOCR and return JSON-style result.
     */
    public String extractFieldsAsJson(String hocrHtml) {
        Map<String, String> fields = extractFields(hocrHtml);
        return String.format(
                "{\"composition\":\"%s\",\"description\":\"%s\"}",
                escapeJson(fields.get("composition")),
                escapeJson(fields.get("description"))
        );
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
