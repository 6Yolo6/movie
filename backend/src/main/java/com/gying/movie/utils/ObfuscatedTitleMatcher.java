package com.gying.movie.utils;

import com.ibm.icu.text.Transliterator;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Full-title alignment only; spelling recognition never selects a collection or transfer scope. */
public final class ObfuscatedTitleMatcher {
    private static final ThreadLocal<Transliterator> SIMPLIFY = ThreadLocal.withInitial(() -> Transliterator.getInstance("Traditional-Simplified"));
    private static final ThreadLocal<Transliterator> PINYIN = ThreadLocal.withInitial(() -> Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"));
    private static final Map<Character, List<String>> PARTS = Map.ofEntries(
        Map.entry('明', List.of("日月")), Map.entry('林', List.of("木木")), Map.entry('森', List.of("木木木")),
        Map.entry('休', List.of("亻木")), Map.entry('好', List.of("女子")), Map.entry('海', List.of("氵每")),
        Map.entry('河', List.of("氵可")), Map.entry('湖', List.of("氵胡")), Map.entry('江', List.of("氵工")),
        Map.entry('说', List.of("讠兑")), Map.entry('诞', List.of("讠延")), Map.entry('语', List.of("讠吾")),
        Map.entry('召', List.of("刀口")), Map.entry('暗', List.of("日音")), Map.entry('师', List.of("丿丨一巾")),
        Map.entry('部', List.of("咅阝")), Map.entry('门', List.of("丶丨𠃍")), Map.entry('爱', List.of("爫冖友")),
        Map.entry('恋', List.of("亦心")), Map.entry('清', List.of("氵青")), Map.entry('情', List.of("忄青")),
        Map.entry('晴', List.of("日青")), Map.entry('请', List.of("讠青")), Map.entry('信', List.of("亻言")),
        Map.entry('侠', List.of("亻夹")), Map.entry('神', List.of("礻申")), Map.entry('仙', List.of("亻山")),
        Map.entry('影', List.of("景彡")), Map.entry('梦', List.of("林夕")), Map.entry('新', List.of("亲斤")),
        Map.entry('战', List.of("占戈")), Map.entry('男', List.of("田力")), Map.entry('怒', List.of("奴心")),
        Map.entry('想', List.of("相心")), Map.entry('忍', List.of("刃心")), Map.entry('思', List.of("田心")),
        Map.entry('体', List.of("亻本")), Map.entry('你', List.of("亻尔")), Map.entry('他', List.of("亻也")),
        Map.entry('她', List.of("女也")), Map.entry('松', List.of("木公")), Map.entry('桥', List.of("木乔")));
    private static final Pattern METADATA = Pattern.compile("(?i)(?:[\\[(（【]?(?:18|19|20)\\d{2}[\\])）】]?|\\b(?:1080p|2160p|720p|4k|8k|bluray|web-dl|webdl|hdtv|remux)\\b)");
    private ObfuscatedTitleMatcher() { }
    public static boolean matches(String name, String title) {
        if (name == null || title == null || name.length()>180 || title.length()>80) return false;
        String expected=compact(title);
        int count=(int) expected.codePoints().filter(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count();
        if (count<4) return false;
        String spelling = METADATA.matcher(name).replaceAll("").trim();
        // Some catalogs prepend the title's sorting initial, e.g. "H 黑暗…".
        // Accept exactly one matching initial followed by whitespace, never an arbitrary prefix.
        var prefix = Pattern.compile("(?i)^([a-z])\\s+(.+)$").matcher(spelling);
        if (prefix.matches() && !expected.isEmpty()) {
            String first = new String(Character.toChars(expected.codePointAt(0)));
            String syllable = compact(PINYIN.get().transliterate(first));
            if (syllable.startsWith(prefix.group(1).toLowerCase(Locale.ROOT))) spelling = prefix.group(2);
        }
        String candidate=compact(spelling);
        if (candidate.isBlank() || candidate.length()>160) return false;
        Map<Integer, Integer> positions = new HashMap<>(); positions.put(0, 0);
        for (int offset=0; offset<expected.length();) {
            int cp=expected.codePointAt(offset); String literal=new String(Character.toChars(cp)); offset+=Character.charCount(cp);
            boolean han=Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN;
            Map<String, Integer> variants=new LinkedHashMap<>(); variants.put(literal, han?1:0);
            if (han) {
                String syllable=compact(PINYIN.get().transliterate(literal));
                if (syllable.matches("[a-z]+")) { variants.put(syllable,0); variants.put(syllable.substring(0,1),0); }
                if (cp<=Character.MAX_VALUE) for (String part:PARTS.getOrDefault((char)cp,List.of())) variants.put(compact(part),1);
            }
            Map<Integer,Integer> next=new HashMap<>();
            for (var entry:positions.entrySet()) for (var variant:variants.entrySet())
                if (candidate.startsWith(variant.getKey(),entry.getKey())) next.merge(entry.getKey()+variant.getKey().length(),entry.getValue()+variant.getValue(),Math::max);
            positions=next;
            if (positions.isEmpty()) return false;
        }
        // Pure initials and broad fuzzy matches are never enough for an automatic title decision.
        return positions.getOrDefault(candidate.length(),0)>=Math.max(3,(count+1)/2);
    }
    private static String compact(String value) {
        return SIMPLIFY.get().transliterate(Normalizer.normalize(value,Normalizer.Form.NFKC))
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]", "");
    }
}
