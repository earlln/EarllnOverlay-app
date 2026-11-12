package com.earlln.overlay.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vosk 출력(소문자, 구두점 없음)을 번역기가 잘 이해하는 문장 형태로 다듬는다.
 * Android 의존성이 없는 순수 Java 클래스라서 단위 테스트가 가능하다.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /** 무음/잡음 구간에서 Vosk가 자주 내놓는 의미 없는 단독 결과 */
    private static final Set<String> NOISE_WORDS = new HashSet<>(Arrays.asList(
        "the", "a", "an", "uh", "um", "huh", "hmm", "mm", "mhm", "ah", "oh", "eh", "er", "hm"
    ));

    /** 문장 중간에 끼는 군더더기 (번역 품질을 떨어뜨림) */
    private static final Pattern FILLERS =
        Pattern.compile("\\b(uh+|um+|erm|hmm+|mhm)\\b", Pattern.CASE_INSENSITIVE);

    /** 같은 단어가 연속 반복되는 STT 오류 (예: "the the the") */
    private static final Pattern REPEATED_WORD =
        Pattern.compile("\\b(\\w+)(\\s+\\1\\b)+", Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /** 단독 "i" 및 "i'm", "i've" 등 → 대문자 I */
    private static final Pattern LONE_I = Pattern.compile("\\bi\\b");

    /** 의문사 */
    private static final Set<String> WH_WORDS = new HashSet<>(Arrays.asList(
        "what", "why", "how", "where", "when", "who", "whom", "whose", "which"
    ));

    /** 조동사: 바로 뒤에 주어가 올 때만 의문문으로 본다 ("Do it now" 같은 명령문 오판 방지) */
    private static final Set<String> AUX_WORDS = new HashSet<>(Arrays.asList(
        "is", "are", "am", "was", "were", "do", "does", "did",
        "can", "could", "will", "would", "should", "shall", "may", "might",
        "have", "has", "had", "isn't", "aren't", "don't", "doesn't", "didn't",
        "can't", "won't", "wouldn't", "shouldn't", "couldn't"
    ));

    private static final Set<String> SUBJECTS = new HashSet<>(Arrays.asList(
        "i", "you", "we", "they", "he", "she", "it", "there", "that", "this",
        "anyone", "anybody", "someone", "somebody", "everyone", "everybody"
    ));

    /** 한 단어짜리 잡음 결과인지 판단 */
    public static boolean isNoise(String raw) {
        if (raw == null) return true;
        String t = raw.trim().toLowerCase(Locale.US);
        if (t.isEmpty()) return true;
        String[] words = MULTI_SPACE.split(t);
        if (words.length == 1) return NOISE_WORDS.contains(words[0]);
        for (String w : words) {
            if (!NOISE_WORDS.contains(w)) return false;
        }
        return true;
    }

    /** 공백/반복/군더더기 정리 (표시용, 문장부호 추가 없음) */
    public static String clean(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        t = FILLERS.matcher(t).replaceAll(" ");
        t = REPEATED_WORD.matcher(t).replaceAll("$1");
        t = MULTI_SPACE.matcher(t).replaceAll(" ").trim();
        t = LONE_I.matcher(t).replaceAll("I");
        return t;
    }

    /**
     * 번역 입력용 정규화: 정리 + 첫 글자 대문자 + 문장 끝 구두점.
     * @param finalResult true면 문장 끝 부호(. 또는 ?)를 붙인다. 부분 결과는 붙이지 않는다.
     */
    public static String forTranslation(String raw, boolean finalResult) {
        String t = clean(raw);
        if (t.isEmpty()) return "";
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        if (finalResult) {
            char last = t.charAt(t.length() - 1);
            if (last != '.' && last != '?' && last != '!') {
                t = t + (looksLikeQuestion(t) ? "?" : ".");
            }
        }
        return t;
    }

    static boolean looksLikeQuestion(String t) {
        String[] w = MULTI_SPACE.split(t.trim().toLowerCase(Locale.US));
        if (w.length < 3) return false;
        // "When I was young..." 같은 종속절 오판을 막기 위해 의문사 + 조동사 조합만 인정 ("why"는 예외)
        if (WH_WORDS.contains(w[0])) return "why".equals(w[0]) || AUX_WORDS.contains(w[1]);
        // "Do it now", "Don't do that" 같은 명령문 제외
        if (("do".equals(w[0]) || "don't".equals(w[0]))
            && ("it".equals(w[1]) || "that".equals(w[1]) || "this".equals(w[1]))) return false;
        return AUX_WORDS.contains(w[0]) && SUBJECTS.contains(w[1]);
    }

    public static int wordCount(String t) {
        if (t == null) return 0;
        String s = t.trim();
        return s.isEmpty() ? 0 : MULTI_SPACE.split(s).length;
    }

    /**
     * 긴 텍스트는 끝부분(가장 최신 단어)이 보이도록 앞쪽을 잘라낸다.
     * TextView의 ellipsize=end는 최신 단어를 숨기기 때문에 사용하지 않는다.
     */
    public static String tail(String t, int maxChars) {
        if (t == null || t.length() <= maxChars) return t;
        int start = t.length() - maxChars;
        Matcher m = Pattern.compile("\\s").matcher(t);
        if (m.find(start) && m.start() < t.length() - 1) start = m.start() + 1;
        return "… " + t.substring(start);
    }
}
