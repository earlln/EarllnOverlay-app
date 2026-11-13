package com.earlln.overlay.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ScreenTextFilterTest {

    @Test
    public void keepsEnglishSentences() {
        List<String> out = ScreenTextFilter.sentences(Arrays.asList(
            "Don't be shy to speak English!",
            "with a\nreal person.",
            "팔로우",
            "10:13",
            "2.2만",
            "Download",
            "Best AI English Tutor"));
        assertEquals(Arrays.asList(
            "Don't be shy to speak English!",
            "with a real person.",
            "Best AI English Tutor"), out);
    }

    @Test
    public void joinsHyphenatedLineBreaks() {
        assertEquals("an example sentence", ScreenTextFilter.normalize("an exam-\nple sentence"));
    }

    @Test
    public void rejectsMostlyKoreanOrNumbers() {
        assertFalse(ScreenTextFilter.isEnglishText("번역 보기 see more"));
        assertFalse(ScreenTextFilter.isEnglishText("2,569 649 3,447 ok"));
        assertTrue(ScreenTextFilter.isEnglishText("A doorbell camera showing women"));
    }

    @Test
    public void removesDuplicates() {
        assertEquals(1, ScreenTextFilter.sentences(Arrays.asList("hello there", "hello  there")).size());
    }
}
