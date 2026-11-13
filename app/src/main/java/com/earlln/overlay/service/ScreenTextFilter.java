package com.earlln.overlay.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 글자 인식(OCR) 결과에서 번역할 만한 영어 문장만 골라낸다.
 * 버튼 이름, 시간, 숫자, 한글 등은 제외한다. (Android 의존성 없음 → 단위 테스트 가능)
 */
public final class ScreenTextFilter {

    private ScreenTextFilter() {}

    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z'’\\-]*[A-Za-z]");
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\w)-\\s*\\n\\s*(\\w)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    static final int MIN_WORDS = 2;
    static final int MIN_LETTERS = 6;

    /** 텍스트 블록 목록 → 번역 대상 문장 목록 (순서 유지, 중복 제거) */
    public static List<String> sentences(List<String> blocks) {
        Set<String> out = new LinkedHashSet<>();
        if (blocks == null) return new ArrayList<>(out);
        for (String b : blocks) {
            String t = normalize(b);
            if (isEnglishText(t)) out.add(t);
        }
        return new ArrayList<>(out);
    }

    /** 줄바꿈으로 끊긴 단어("exam-\nple")를 잇고 공백을 정리 */
    static String normalize(String block) {
        if (block == null) return "";
        String t = HYPHEN_BREAK.matcher(block).replaceAll("$1$2");
        return WHITESPACE.matcher(t).replaceAll(" ").trim();
    }

    static boolean isEnglishText(String t) {
        if (t == null || t.isEmpty()) return false;

        int words = 0;
        Matcher m = ENGLISH_WORD.matcher(t);
        while (m.find()) words++;
        if (words < MIN_WORDS) return false;

        int latin = 0, otherLetters = 0, digitsSymbols = 0, nonSpace = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) continue;
            nonSpace++;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if (Character.isLetter(c)) otherLetters++;
            else digitsSymbols++;
        }
        if (latin < MIN_LETTERS) return false;
        // 한글 등 다른 문자가 많으면 제외 (영어 비율 70% 이상)
        if (latin < (latin + otherLetters) * 0.7) return false;
        // 숫자/기호 위주(시간, 조회수 등)면 제외
        return digitsSymbols * 2 <= nonSpace;
    }
}
