package com.earlln.overlay.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LlmErrorsTest {

    private static final String CREDIT = "400: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
        + "\"message\":\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.\"}}";

    @Test
    public void extractsMessageField() {
        assertTrue(LlmErrors.extractMessage(CREDIT).startsWith("Your credit balance is too low"));
        assertEquals("plain text", LlmErrors.extractMessage("plain text"));
    }

    @Test
    public void creditBalanceIsExplainedInKorean() {
        assertTrue(LlmErrors.friendly(400, CREDIT).startsWith("Anthropic 크레딧 잔액이 부족합니다"));
    }

    @Test
    public void onlyFallbackErrorsTriggerRetryWithoutFallbacks() {
        assertFalse(LlmErrors.isFallbackRejection(CREDIT));
        assertTrue(LlmErrors.isFallbackRejection(
            "400: {\"error\":{\"message\":\"fallbacks: Extra inputs are not permitted\"}}"));
    }

    @Test
    public void unknownErrorShowsServerMessage() {
        assertEquals("Claude 오류 400: something odd",
            LlmErrors.friendly(400, "400: {\"error\":{\"message\":\"something odd\"}}"));
    }
}
