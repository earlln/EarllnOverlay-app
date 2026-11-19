package com.earlln.overlay.note;

import java.util.List;

/** 자막 노트에 쌓이는 확정 문장 1개 (영어 원문 + 번역) */
public final class NoteEntry {

    public enum KoState { NONE, PENDING, DONE, FAILED }

    private static long nextId = 1;

    public final long id;
    public final String en;
    /** true: 화면 글자 번역(📷)으로 추가된 문장, false: 음성 인식 문장 */
    public final boolean fromScreen;
    public String ko;
    public KoState koState = KoState.NONE;
    /** true: 번역이 ✨ AI 번역(Claude)으로 교체됨 */
    public boolean llm = false;

    public NoteEntry(String en) {
        this(en, false);
    }

    public NoteEntry(String en, boolean fromScreen) {
        this.id = nextId++;
        this.en = en;
        this.fromScreen = fromScreen;
    }

    /** 표시 모드에 맞춰 문장 하나를 텍스트로 만든다 (복사/공유용) */
    public String toText(String langMode) {
        boolean hasKo = ko != null && !ko.isEmpty();
        if ("en".equals(langMode)) return en;
        if ("ko".equals(langMode)) return hasKo ? ko : en;
        return hasKo ? en + "\n" + ko : en;
    }

    /** 전체 기록을 문장 사이 빈 줄로 구분한 텍스트로 만든다 */
    public static String transcript(List<NoteEntry> entries, String langMode) {
        StringBuilder sb = new StringBuilder();
        for (NoteEntry e : entries) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(e.toText(langMode));
        }
        return sb.toString();
    }
}
