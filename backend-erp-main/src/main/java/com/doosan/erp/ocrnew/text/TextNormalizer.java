package com.doosan.erp.ocrnew.text;

public class TextNormalizer {
    public String normalize(String s) {
        if (s == null) return "";
        String r = s;
        r = r.replaceAll("\u00A0", " ");
        r = r.replaceAll("\u200B|\u200C|\u200D|\u2060", "");
        r = r.replaceAll("\s{2,}", " ");
        // Textile normalization
        r = r.replaceAll("(?i)reviscose", "Reviscose");
        r = r.replaceAll("(?i)revisco\\s*se", "Reviscose");
        r = r.replaceAll("(?i)rculose", "rculose");
        r = r.replaceAll("(?i)ci\\s*rculose", "circulose");
        r = r.replaceAll("(?i)recy\\s*cled", "recycled");
        // Join spaced percent like 80 / 20% -> 80/20%
        r = r.replaceAll("(?i)(\\d+)\\s*/\\s*(\\d+)%", "$1/$2%");
        // Join numeric patterns 150 x94 -> 150x94
        r = r.replaceAll("(?i)(\\d+)\\s*[xX]\\s*(\\d+)", "$1x$2");
        // Join units 75g / sm -> 75g/sm
        r = r.replaceAll("(?i)(g)\\s*/\\s*(sm)", "$1/$2");
        // 55" CW -> 55"CW
        r = r.replaceAll("(?i)\"\\s*([A-Za-z]{1,3})", "\"$1");
        return r.trim();
    }
}
