package com.earlln.overlay.note;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class NoteEntryTest {

    private static NoteEntry entry(String en, String ko) {
        NoteEntry e = new NoteEntry(en);
        e.ko = ko;
        return e;
    }

    @Test
    public void textPerMode() {
        NoteEntry e = entry("Hello there.", "안녕하세요.");
        assertEquals("Hello there.\n안녕하세요.", e.toText("both"));
        assertEquals("안녕하세요.", e.toText("ko"));
        assertEquals("Hello there.", e.toText("en"));
    }

    @Test
    public void missingTranslationFallsBackToEnglish() {
        NoteEntry e = entry("Hello there.", null);
        assertEquals("Hello there.", e.toText("both"));
        assertEquals("Hello there.", e.toText("ko"));
    }

    @Test
    public void transcriptJoinsWithBlankLines() {
        String t = NoteEntry.transcript(Arrays.asList(
            entry("One.", "하나."), entry("Two.", null)), "both");
        assertEquals("One.\n하나.\n\nTwo.", t);
    }

    @Test
    public void idsAreUnique() {
        NoteEntry a = new NoteEntry("a");
        NoteEntry b = new NoteEntry("b");
        assertEquals(1, b.id - a.id);
    }
}
