package com.earlln.overlay.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude API 오류 메시지를 사용자에게 보여줄 한국어 안내로 바꾼다. (Android 의존성 없음 → 단위 테스트 가능)
 * SDK 예외 메시지는 보통 '400: {"type":"error","error":{"type":"...","message":"..."}}' 형태다.
 */
public final class LlmErrors {

    private LlmErrors() {}

    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 오류 본문에서 message 필드만 꺼냄 (없으면 원문) */
    public static String extractMessage(String raw) {
        if (raw == null) return "";
        Matcher m = MESSAGE.matcher(raw);
        String msg = m.find() ? m.group(1) : raw;
        return msg.replace("\\\"", "\"").replace("\\n", " ").trim();
    }

    /** 서버가 fallbacks 파라미터/베타 자체를 거부한 400 인지 (이 경우만 fallbacks 없이 재시도) */
    public static boolean isFallbackRejection(String raw) {
        return raw != null && raw.toLowerCase(Locale.US).contains("fallback");
    }

    /** 사용자 안내 문구 */
    public static String friendly(int statusCode, String raw) {
        String msg = extractMessage(raw);
        String lower = msg.toLowerCase(Locale.US);
        if (lower.contains("credit balance")) {
            return "Anthropic 크레딧 잔액이 부족합니다\n"
                + "console.anthropic.com → Plans & Billing 에서 충전한 뒤 ⚙️ 설정에서 Claude 키를 다시 저장하세요";
        }
        if (statusCode == 401 || lower.contains("api key") || lower.contains("x-api-key")) {
            return "Claude API 키가 올바르지 않습니다\n⚙️ 설정에서 키를 다시 확인해 저장하세요";
        }
        if (statusCode == 403) {
            return "이 API 키에 권한이 없습니다 (" + msg + ")";
        }
        if (statusCode == 404 || (lower.contains("model") && lower.contains("not"))) {
            return "이 계정에서 Claude Opus 5.5 모델을 사용할 수 없습니다 (" + msg + ")";
        }
        if (statusCode == 429) {
            return "Claude 사용 한도(요청 수/토큰)를 넘었습니다. 잠시 후 다시 시도합니다";
        }
        return "Claude 오류 " + statusCode + ": " + msg;
    }
}
