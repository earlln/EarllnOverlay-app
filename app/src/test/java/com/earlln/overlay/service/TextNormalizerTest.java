package com.earlln.overlay.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextNormalizerTest {

    @Test
    public void noiseDetection() {
        assertTrue(TextNormalizer.isNoise("the"));
        assertTrue(TextNormalizer.isNoise("  uh  "));
        assertTrue(TextNormalizer.isNoise("uh huh"));
        assertTrue(TextNormalizer.isNoise(""));
        assertTrue(TextNormalizer.isNoise(null));
        assertFalse(TextNormalizer.isNoise("hello"));
        assertFalse(TextNormalizer.isNoise("the end"));
    }

    @Test
    public void cleanRemovesFillersAndRepeats() {
        assertEquals("I think it's good", TextNormalizer.clean("um i think think it's uh good"));
        assertEquals("the cat", TextNormalizer.clean("the the the cat"));
        assertEquals("I'm here and I've been", TextNormalizer.clean("i'm here and i've been"));
    }

    @Test
    public void finalSentenceGetsCapitalAndPeriod() {
        assertEquals("This is a test.", TextNormalizer.forTranslation("this is a test", true));
        assertEquals("This is a test", TextNormalizer.forTranslation("this is a test", false));
    }

    @Test
    public void questionDetection() {
        assertEquals("Do you know him?", TextNormalizer.forTranslation("do you know him", true));
        assertEquals("What is that thing?", TextNormalizer.forTranslation("what is that thing", true));
        assertEquals("Why not try it?", TextNormalizer.forTranslation("why not try it", true));
        // 명령문/종속절은 의문문으로 오판하지 않음
        assertEquals("Do it right now.", TextNormalizer.forTranslation("do it right now", true));
        assertEquals("When I was young I played.",
            TextNormalizer.forTranslation("when i was young i played", true));
    }

    @Test
    public void tailKeepsNewestWords() {
        String s = "one two three four five six seven eight nine ten";
        String t = TextNormalizer.tail(s, 20);
        assertTrue(t.startsWith("… "));
        assertTrue(t.endsWith("nine ten"));
        assertEquals(s, TextNormalizer.tail(s, 100));
    }

    @Test
    public void wordCount() {
        assertEquals(0, TextNormalizer.wordCount("  "));
        assertEquals(3, TextNormalizer.wordCount(" a  b c "));
    }
}
