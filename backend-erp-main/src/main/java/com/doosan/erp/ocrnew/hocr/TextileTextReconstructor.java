package com.doosan.erp.ocrnew.hocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconstructs fragmented text from hOCR output, with textile-specific logic.
 * Handles:
 * - Merging split words based on bounding box proximity
 * - Joining continuation lines
 * - Normalizing textile terminology
 * - Multi-page text continuation
 */
public class TextileTextReconstructor {
    private static final Logger log = LoggerFactory.getLogger(TextileTextReconstructor.class);

    // Configuration for merging
    private int maxHorizontalGap = 15;      // Max pixel gap to consider words as continuation
    private int maxVerticalGap = 25;        // Max pixel gap between lines
    private int maxLeftAlignDiff = 50;      // Max difference in left alignment for continuation
    private float avgCharWidth = 8.0f;      // Average character width in pixels

    // Known textile word fragments and their complete forms
    private static final Map<String, String> FRAGMENT_DICTIONARY = new LinkedHashMap<>();
    
    // Pattern for common textile measurements
    private static final Pattern MEASUREMENT_PATTERN = Pattern.compile(
            "(\\d+)\\s*[xX×]\\s*(\\d+)"
    );
    
    // Pattern for percentage
    private static final Pattern PERCENT_PATTERN = Pattern.compile(
            "(\\d+)\\s*[/\\\\]\\s*(\\d+)\\s*%"
    );

    static {
        // Fiber/Material fragments
        FRAGMENT_DICTIONARY.put("rculose", "circulose");
        FRAGMENT_DICTIONARY.put("irculose", "circulose");
        FRAGMENT_DICTIONARY.put("iculose", "circulose");
        FRAGMENT_DICTIONARY.put("culose", "circulose");
        FRAGMENT_DICTIONARY.put("ulose", "circulose");
        FRAGMENT_DICTIONARY.put("yester", "polyester");
        FRAGMENT_DICTIONARY.put("olyester", "polyester");
        FRAGMENT_DICTIONARY.put("lyester", "polyester");
        FRAGMENT_DICTIONARY.put("ester", "polyester");
        FRAGMENT_DICTIONARY.put("iscose", "viscose");
        FRAGMENT_DICTIONARY.put("scose", "viscose");
        FRAGMENT_DICTIONARY.put("eviscose", "reviscose");
        FRAGMENT_DICTIONARY.put("viscose", "viscose");
        FRAGMENT_DICTIONARY.put("otton", "cotton");
        FRAGMENT_DICTIONARY.put("tton", "cotton");
        FRAGMENT_DICTIONARY.put("lastane", "elastane");
        FRAGMENT_DICTIONARY.put("astane", "elastane");
        FRAGMENT_DICTIONARY.put("pandex", "spandex");
        FRAGMENT_DICTIONARY.put("andex", "spandex");
        FRAGMENT_DICTIONARY.put("ylon", "nylon");
        FRAGMENT_DICTIONARY.put("yLon", "nylon");
        FRAGMENT_DICTIONARY.put("inen", "linen");
        FRAGMENT_DICTIONARY.put("amie", "ramie");
        FRAGMENT_DICTIONARY.put("encel", "tencel");
        FRAGMENT_DICTIONARY.put("encel", "tencel");
        FRAGMENT_DICTIONARY.put("yocell", "lyocell");
        FRAGMENT_DICTIONARY.put("ocell", "lyocell");
        FRAGMENT_DICTIONARY.put("odal", "modal");
        FRAGMENT_DICTIONARY.put("amboo", "bamboo");
        FRAGMENT_DICTIONARY.put("ecycled", "recycled");
        FRAGMENT_DICTIONARY.put("cycled", "recycled");
        FRAGMENT_DICTIONARY.put("ycled", "recycled");
        FRAGMENT_DICTIONARY.put("rganic", "organic");
        FRAGMENT_DICTIONARY.put("ganic", "organic");

        // Garment terms
        FRAGMENT_DICTIONARY.put("escription", "description");
        FRAGMENT_DICTIONARY.put("cription", "description");
        FRAGMENT_DICTIONARY.put("omposition", "composition");
        FRAGMENT_DICTIONARY.put("mposition", "composition");
        FRAGMENT_DICTIONARY.put("osition", "composition");
        FRAGMENT_DICTIONARY.put("abric", "fabric");
        FRAGMENT_DICTIONARY.put("bric", "fabric");
        FRAGMENT_DICTIONARY.put("aterial", "material");
        FRAGMENT_DICTIONARY.put("terial", "material");
        FRAGMENT_DICTIONARY.put("erial", "material");

        // Treatment terms
        FRAGMENT_DICTIONARY.put("ashed", "washed");
        FRAGMENT_DICTIONARY.put("shed", "washed");
        FRAGMENT_DICTIONARY.put("eached", "bleached");
        FRAGMENT_DICTIONARY.put("ached", "bleached");
        FRAGMENT_DICTIONARY.put("rinted", "printed");
        FRAGMENT_DICTIONARY.put("inted", "printed");
        FRAGMENT_DICTIONARY.put("yed", "dyed");
    }

    // Known complete words for validation
    private static final Set<String> KNOWN_TEXTILE_WORDS = new HashSet<>();
    
    static {
        KNOWN_TEXTILE_WORDS.addAll(Arrays.asList(
                "circulose", "viscose", "reviscose", "polyester", "cotton", "nylon",
                "elastane", "spandex", "lycra", "linen", "silk", "wool", "cashmere",
                "rayon", "modal", "tencel", "lyocell", "bamboo", "hemp", "ramie",
                "acrylic", "polyamide", "polypropylene", "recycled", "organic",
                "fabric", "material", "composition", "description", "washed",
                "bleached", "printed", "dyed", "finished", "coated", "laminated",
                "gsm", "g/sm", "g/m2", "denier", "count", "ply", "twist"
        ));
    }

    public TextileTextReconstructor() {}

    public TextileTextReconstructor(int maxHorizontalGap, int maxVerticalGap, int maxLeftAlignDiff) {
        this.maxHorizontalGap = maxHorizontalGap;
        this.maxVerticalGap = maxVerticalGap;
        this.maxLeftAlignDiff = maxLeftAlignDiff;
    }

    /**
     * Reconstruct text from hOCR pages, merging fragmented words and lines.
     * 
     * @param pages List of HocrPage objects from parser
     * @return Reconstructed text with fragments merged
     */
    public String reconstructText(List<HocrPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return "";
        }

        List<HocrLine> allLines = new ArrayList<>();
        for (HocrPage page : pages) {
            allLines.addAll(page.getLines());
        }

        return reconstructFromLines(allLines);
    }

    /**
     * Reconstruct text from lines, handling continuation and fragment merging.
     */
    public String reconstructFromLines(List<HocrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        HocrLine prevLine = null;

        for (HocrLine line : lines) {
            // First, merge fragmented words within the line
            String lineText = mergeFragmentedWordsInLine(line);
            
            if (prevLine != null) {
                // Check if this line continues from previous
                if (shouldMergeLines(prevLine, line)) {
                    // Check if we need to merge last word of prev with first word of current
                    String merged = tryMergeLineEndings(result.toString(), lineText);
                    if (merged != null) {
                        // Clear and append merged result
                        result = new StringBuilder(merged);
                    } else {
                        // Just append with space
                        result.append(' ').append(lineText);
                    }
                } else {
                    // New paragraph or section
                    result.append('\n').append(lineText);
                }
            } else {
                result.append(lineText);
            }

            prevLine = line;
        }

        // Final normalization
        return normalizeTextileText(result.toString());
    }

    /**
     * Merge fragmented words within a single line based on horizontal proximity.
     */
    public String mergeFragmentedWordsInLine(HocrLine line) {
        List<HocrWord> words = line.getWords();
        if (words == null || words.isEmpty()) {
            return "";
        }

        if (words.size() == 1) {
            return normalizeFragment(words.get(0).getText());
        }

        StringBuilder result = new StringBuilder();
        HocrWord prevWord = null;

        for (HocrWord word : words) {
            String text = word.getText();
            
            if (prevWord != null) {
                // Calculate horizontal gap
                int gap = word.getX() - prevWord.getRight();
                
                // Estimate expected gap based on character width
                float expectedGap = avgCharWidth * 0.5f;
                
                if (gap < maxHorizontalGap && gap < expectedGap * 2) {
                    // Words are close, check if they should be merged
                    if (shouldMergeWords(prevWord.getText(), text)) {
                        // Merge without space
                        result.append(text);
                    } else {
                        result.append(' ').append(text);
                    }
                } else {
                    result.append(' ').append(text);
                }
            } else {
                result.append(text);
            }

            prevWord = word;
        }

        return result.toString().trim();
    }

    /**
     * Check if two words should be merged (one is fragment of the other).
     */
    private boolean shouldMergeWords(String word1, String word2) {
        if (word1 == null || word2 == null) return false;
        
        word1 = word1.trim();
        word2 = word2.trim();
        
        if (word1.isEmpty() || word2.isEmpty()) return false;

        // Check if word1 ends with hyphen
        if (word1.endsWith("-")) {
            return true;
        }

        // Check if combined word is a known textile term
        String combined = word1 + word2;
        String combinedLower = combined.toLowerCase();
        if (KNOWN_TEXTILE_WORDS.contains(combinedLower)) {
            return true;
        }

        // Check if word2 is a known fragment
        String w2Lower = word2.toLowerCase();
        if (FRAGMENT_DICTIONARY.containsKey(w2Lower)) {
            String expectedFull = FRAGMENT_DICTIONARY.get(w2Lower);
            if (combinedLower.endsWith(expectedFull) || expectedFull.startsWith(combinedLower)) {
                return true;
            }
        }

        // Check if word1 is short (likely fragment) and word2 starts lowercase
        if (word1.length() <= 3 && Character.isLowerCase(word2.charAt(0))) {
            // Check vowel pattern - fragments often lack complete vowel structure
            if (!hasCompleteVowelPattern(word1)) {
                return true;
            }
        }

        // Check if word2 starts with lowercase and is continuation
        if (Character.isLowerCase(word2.charAt(0)) && 
            !isStandaloneWord(word2) && 
            word1.length() <= 4) {
            return true;
        }

        return false;
    }

    private boolean hasCompleteVowelPattern(String word) {
        if (word == null || word.length() < 2) return false;
        String lower = word.toLowerCase();
        int vowels = 0;
        for (char c : lower.toCharArray()) {
            if ("aeiou".indexOf(c) >= 0) vowels++;
        }
        // Word should have at least 1 vowel per 3 characters
        return vowels >= Math.max(1, word.length() / 3);
    }

    private boolean isStandaloneWord(String word) {
        if (word == null || word.isEmpty()) return false;
        String lower = word.toLowerCase();
        // Common standalone short words
        return Set.of("a", "an", "the", "of", "to", "in", "on", "at", "by", "for",
                "and", "or", "but", "not", "no", "yes", "is", "are", "was", "were",
                "be", "been", "being", "have", "has", "had", "do", "does", "did",
                "with", "from", "into", "onto", "upon").contains(lower);
    }

    /**
     * Check if two lines should be merged (continuation).
     */
    private boolean shouldMergeLines(HocrLine prevLine, HocrLine currentLine) {
        if (prevLine == null || currentLine == null) return false;

        // Same page check
        if (prevLine.getPageNum() != currentLine.getPageNum()) {
            // Cross-page continuation - check if current is at top of new page
            // and previous was at bottom
            return currentLine.getY() < prevLine.getHeight() * 0.3;
        }

        // Vertical gap check
        int verticalGap = currentLine.getY() - prevLine.getBottom();
        if (verticalGap > maxVerticalGap) {
            return false;
        }

        // Left alignment check
        int alignDiff = Math.abs(currentLine.getX() - prevLine.getX());
        if (alignDiff > maxLeftAlignDiff) {
            return false;
        }

        // Content-based heuristics
        String prevText = prevLine.getText().trim();
        String currText = currentLine.getText().trim();

        if (prevText.isEmpty() || currText.isEmpty()) return false;

        // Previous line ends without terminal punctuation
        char lastChar = prevText.charAt(prevText.length() - 1);
        boolean endsWithTerminal = ".!?;:".indexOf(lastChar) >= 0;

        // Current line starts with lowercase
        char firstChar = currText.charAt(0);
        boolean startsLowercase = Character.isLowerCase(firstChar);

        // Strong indicator: no terminal + lowercase start
        if (!endsWithTerminal && startsLowercase) {
            return true;
        }

        // Check if previous ends with fragment
        String[] prevWords = prevText.split("\\s+");
        String lastWord = prevWords[prevWords.length - 1];
        if (looksLikeFragment(lastWord)) {
            return true;
        }

        // Check if current starts with fragment continuation
        String[] currWords = currText.split("\\s+");
        String firstWord = currWords[0];
        if (FRAGMENT_DICTIONARY.containsKey(firstWord.toLowerCase())) {
            return true;
        }

        return false;
    }

    private boolean looksLikeFragment(String word) {
        if (word == null || word.isEmpty()) return false;
        
        // Ends with hyphen
        if (word.endsWith("-")) return true;
        
        // Very short without vowel
        if (word.length() <= 2 && !hasCompleteVowelPattern(word)) return true;
        
        // Short word ending with consonant (might be truncated)
        if (word.length() <= 3) {
            char last = Character.toLowerCase(word.charAt(word.length() - 1));
            if ("bcdfghjklmnpqrstvwxyz".indexOf(last) >= 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Try to merge the ending of accumulated text with the beginning of new text.
     * Returns merged string or null if no merge needed.
     */
    private String tryMergeLineEndings(String accumulated, String newText) {
        if (accumulated == null || accumulated.isEmpty()) return null;
        if (newText == null || newText.isEmpty()) return accumulated;

        accumulated = accumulated.trim();
        newText = newText.trim();

        // Get last word of accumulated
        int lastSpace = accumulated.lastIndexOf(' ');
        String lastWord = lastSpace >= 0 ? accumulated.substring(lastSpace + 1) : accumulated;
        String prefix = lastSpace >= 0 ? accumulated.substring(0, lastSpace + 1) : "";

        // Get first word of new text
        int firstSpace = newText.indexOf(' ');
        String firstWord = firstSpace >= 0 ? newText.substring(0, firstSpace) : newText;
        String suffix = firstSpace >= 0 ? newText.substring(firstSpace) : "";

        // Check if they should be merged
        if (shouldMergeWords(lastWord, firstWord)) {
            // Remove hyphen if present
            if (lastWord.endsWith("-")) {
                lastWord = lastWord.substring(0, lastWord.length() - 1);
            }
            String merged = lastWord + firstWord;
            merged = normalizeFragment(merged);
            return prefix + merged + suffix;
        }

        return null;
    }

    /**
     * Normalize a single fragment using the dictionary.
     */
    public String normalizeFragment(String text) {
        if (text == null || text.isEmpty()) return text;

        String lower = text.toLowerCase();
        
        // Direct lookup
        if (FRAGMENT_DICTIONARY.containsKey(lower)) {
            // Preserve original case pattern
            String normalized = FRAGMENT_DICTIONARY.get(lower);
            if (Character.isUpperCase(text.charAt(0))) {
                normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            }
            return normalized;
        }

        // Check if text ends with a known fragment
        for (Map.Entry<String, String> entry : FRAGMENT_DICTIONARY.entrySet()) {
            String fragment = entry.getKey();
            String full = entry.getValue();
            if (lower.endsWith(fragment) && lower.length() < full.length()) {
                // This might be a partial match
                int prefixLen = lower.length() - fragment.length();
                String expectedPrefix = full.substring(0, full.length() - fragment.length());
                if (expectedPrefix.startsWith(lower.substring(0, prefixLen))) {
                    return full;
                }
            }
        }

        return text;
    }

    /**
     * Apply comprehensive textile text normalization.
     */
    public String normalizeTextileText(String text) {
        if (text == null || text.isEmpty()) return text;

        String r = text;

        // FIRST: Merge hyphenated fragments like "ZCX56027-ci" + "rculose"
        r = mergeHyphenatedFragments(r);
        
        // SECOND: Remove duplicate adjacent words
        r = removeDuplicateAdjacentWords(r);

        // Fix common OCR errors in textile terms
        // Keep "Revisco Viscose" as is (don't merge)
        // "Reviscose" alone (OCR error) -> "Revisco Viscose"
        r = r.replaceAll("(?i)\\bReviscose\\s+circulose\\b", "Revisco Viscose with circulose");
        r = r.replaceAll("(?i)\\bReviscose\\s+viscose\\b", "Revisco Viscose");
        r = r.replaceAll("(?i)\\bReviscose\\b(?!\\s+Viscose)", "Revisco Viscose");
        r = r.replaceAll("(?i)\\breviscos\\b", "Revisco");
        // Fix "withc" typo
        r = r.replaceAll("(?i)\\bwithc\\b", "with");
        r = r.replaceAll("(?i)\\bviscose\\s+with\\s+c\\s+circulose\\b", "Viscose with circulose");
        r = r.replaceAll("(?i)\\bviscose\\s+with\\s+circulose\\b", "Viscose with circulose");
        // Other OCR typos
        r = r.replaceAll("(?i)\\brecyceld\\b", "recycled");
        r = r.replaceAll("(?i)\\bpolyster\\b", "polyester");
        r = r.replaceAll("(?i)\\bplolyester\\b", "polyester");
        r = r.replaceAll("(?i)\\bpolester\\b", "polyester");
        r = r.replaceAll("(?i)\\bcirculose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcirculos\\b", "circulose");
        r = r.replaceAll("(?i)\\birculose\\b", "circulose");
        
        // Fix measurement formats
        // "150 x94" -> "150x94"
        r = r.replaceAll("(\\d+)\\s*[xX×]\\s*(\\d+)", "$1x$2");
        
        // "75g /sm" -> "75g/sm"
        r = r.replaceAll("(\\d+)\\s*g\\s*/\\s*sm", "$1g/sm");
        r = r.replaceAll("(\\d+)\\s*g\\s*/\\s*m2", "$1g/m2");
        r = r.replaceAll("(\\d+)\\s*gsm", "$1gsm");
        
        // Fix percentage formats
        // "80 /20 %" -> "80/20%"
        r = r.replaceAll("(\\d+)\\s*/\\s*(\\d+)\\s*%", "$1/$2%");
        // "80/ 20%" -> "80/20%"
        r = r.replaceAll("(\\d+)\\s*/\\s*(\\d+)%", "$1/$2%");
        
        // Fix denier/count formats
        // "20d x 45s" -> "20dx45s"
        r = r.replaceAll("(\\d+)\\s*[dD]\\s*[xX×]\\s*(\\d+)\\s*[sS]", "$1dx$2s");
        
        // Fix width format
        // "5 5'" -> "55'"
        r = r.replaceAll("(\\d)\\s+(\\d)'", "$1$2'");
        // "55 '" -> "55'"
        r = r.replaceAll("(\\d+)\\s+'", "$1'");
        // "55' CW" -> "55'CW"
        r = r.replaceAll("(\\d+)'\\s+CW", "$1'CW");
        
        // Fix common split patterns
        r = r.replaceAll("(?i)\\bci\\s*rculose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcir\\s*culose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcirc\\s*ulose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcircu\\s*lose\\b", "circulose");
        r = r.replaceAll("(?i)\\bcircul\\s*ose\\b", "circulose");
        
        r = r.replaceAll("(?i)\\bpoly\\s*ester\\b", "polyester");
        r = r.replaceAll("(?i)\\bpolye\\s*ster\\b", "polyester");
        r = r.replaceAll("(?i)\\bpolyes\\s*ter\\b", "polyester");
        r = r.replaceAll("(?i)\\bpolyest\\s*er\\b", "polyester");
        
        r = r.replaceAll("(?i)\\bvis\\s*cose\\b", "viscose");
        r = r.replaceAll("(?i)\\bvisc\\s*ose\\b", "viscose");
        r = r.replaceAll("(?i)\\bvisco\\s*se\\b", "viscose");
        
        r = r.replaceAll("(?i)\\brec\\s*ycled\\b", "recycled");
        r = r.replaceAll("(?i)\\brecy\\s*cled\\b", "recycled");
        r = r.replaceAll("(?i)\\brecyc\\s*led\\b", "recycled");
        
        // Clean up multiple spaces
        r = r.replaceAll("\\s+", " ").trim();
        
        // Final deduplication pass
        r = removeDuplicateAdjacentWords(r);

        return r;
    }
    
    /**
     * Merge hyphenated fragments like "ZCX56027-ci" followed by "rculose" or "Solid rculose"
     */
    private String mergeHyphenatedFragments(String text) {
        if (text == null) return text;
        
        // Pattern: word ending with "-ci" followed by "Solid rculose" or "rculose"
        // e.g., "ZCX56027-ci Solid rculose" -> "ZCX56027-circulose" (remove Solid as noise)
        // e.g., "ZCX56027-ci Solid circulose" -> "ZCX56027-circulose" (remove Solid as noise)
        text = text.replaceAll("(?i)(\\w+-ci)\\s+Solid\\s+rculose\\b", "$1rculose");
        text = text.replaceAll("(?i)(\\w+-ci)\\s+Solid\\s+circulose\\b", "$1rculose");
        text = text.replaceAll("(?i)(\\w+-ci)\\s+rculose\\b", "$1rculose");
        text = text.replaceAll("(?i)(\\w+-cir)\\s+culose\\b", "$1culose");
        text = text.replaceAll("(?i)(\\w+-circ)\\s+ulose\\b", "$1ulose");
        
        // Handle standalone fragments
        text = text.replaceAll("(?i)\\bci\\s+rculose\\b", "circulose");
        text = text.replaceAll("(?i)\\bcir\\s+culose\\b", "circulose");
        text = text.replaceAll("(?i)\\brculose\\b", "circulose");
        text = text.replaceAll("(?i)\\b_culose\\b", "circulose");
        
        // More generic hyphen-fragment merging
        for (Map.Entry<String, String> entry : FRAGMENT_DICTIONARY.entrySet()) {
            String fragment = entry.getKey();
            String full = entry.getValue();
            // Pattern: something-X followed by fragment that completes it
            String pattern = "(\\w+-)\\s+" + Pattern.quote(fragment) + "\\b";
            text = text.replaceAll("(?i)" + pattern, "$1" + fragment);
        }
        
        return text;
    }
    
    /**
     * Remove duplicate adjacent words (case-insensitive).
     * e.g., "Revisco Viscose circulose Revisco Viscose" -> "Revisco Viscose with circulose"
     */
    private String removeDuplicateAdjacentWords(String text) {
        if (text == null || text.isEmpty()) return text;
        
        String[] words = text.split("\\s+");
        if (words.length <= 1) return text;
        
        StringBuilder result = new StringBuilder();
        Set<String> recentWords = new LinkedHashSet<>();
        int windowSize = 3; // Look back window for deduplication
        
        for (String word : words) {
            String wordLower = word.toLowerCase();
            
            // Check if this word (or similar) was recently seen
            boolean isDuplicate = false;
            String toRemove = null;
            
            for (String recent : recentWords) {
                if (recent.equalsIgnoreCase(wordLower)) {
                    isDuplicate = true;
                    break;
                }
                // Check for partial matches - but NOT for "revisco" vs "viscose" (different words)
                if (recent.length() >= 6 && wordLower.length() >= 6) {
                    // Only match if one is prefix of other AND they share same root
                    if (recent.startsWith(wordLower) || wordLower.startsWith(recent)) {
                        // Skip if they are different root words (revisco vs viscose)
                        if (!areSameRootWord(recent, wordLower)) {
                            continue;
                        }
                        // Keep the longer one
                        if (wordLower.length() > recent.length()) {
                            toRemove = recent;
                        } else {
                            isDuplicate = true;
                        }
                        break;
                    }
                }
            }
            
            if (toRemove != null) {
                recentWords.remove(toRemove);
            }
            
            if (!isDuplicate) {
                if (result.length() > 0) result.append(' ');
                result.append(word);
                recentWords.add(wordLower);
                
                // Maintain window size
                if (recentWords.size() > windowSize) {
                    Iterator<String> it = recentWords.iterator();
                    it.next();
                    it.remove();
                }
            }
        }
        
        return result.toString();
    }
    
    /**
     * Check if two words are the same root (not different words like revisco vs viscose)
     */
    private boolean areSameRootWord(String a, String b) {
        // revisco and viscose are different words
        if ((a.startsWith("revis") && b.startsWith("visc")) ||
            (a.startsWith("visc") && b.startsWith("revis"))) {
            return false;
        }
        return true;
    }

    // Getters and setters for configuration
    public int getMaxHorizontalGap() { return maxHorizontalGap; }
    public void setMaxHorizontalGap(int maxHorizontalGap) { this.maxHorizontalGap = maxHorizontalGap; }
    public int getMaxVerticalGap() { return maxVerticalGap; }
    public void setMaxVerticalGap(int maxVerticalGap) { this.maxVerticalGap = maxVerticalGap; }
    public int getMaxLeftAlignDiff() { return maxLeftAlignDiff; }
    public void setMaxLeftAlignDiff(int maxLeftAlignDiff) { this.maxLeftAlignDiff = maxLeftAlignDiff; }
    public float getAvgCharWidth() { return avgCharWidth; }
    public void setAvgCharWidth(float avgCharWidth) { this.avgCharWidth = avgCharWidth; }
}
