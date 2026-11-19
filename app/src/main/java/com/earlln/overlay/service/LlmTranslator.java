package com.earlln.overlay.service;

import android.os.Handler;
import android.util.Log;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude(Opus 5.5)로 자막 한 줄을 앞뒤 문맥과 함께 자연스러운 한국어로 번역한다.
 *
 *  - 기기 번역(ML Kit)이 먼저 표시되고, 이 결과가 오면 그 문장의 번역을 교체한다.
 *  - 실패(네트워크/거절 등)하면 콜백에 null → 기기 번역을 그대로 유지.
 *  - API 키 오류면 이번 실행 동안 비활성화하고 알린다.
 * 콜백은 메인 스레드에서 호출된다.
 */
final class LlmTranslator {

    interface Callback {
        /** ko == null 이면 실패 (기존 번역 유지) */
        void onResult(String ko);
    }

    interface StatusListener {
        void onStatus(String msg);
    }

    private static final String TAG = "EarllnOverlay";
    private static final String MODEL = "claude-opus-5-5";

    private static final String SYSTEM_PROMPT =
        "You translate English video subtitles into Korean for a Korean viewer watching in real time.\n"
        + "The English comes from automatic speech recognition or on-screen text recognition, "
        + "so it can contain misheard words, missing punctuation, or fragments. "
        + "Use the earlier lines as context to infer what was actually meant, "
        + "and translate the meaning naturally rather than word by word.\n"
        + "Write the Korean the way a professional subtitle translator would: natural spoken Korean, "
        + "concise enough to read at a glance, with a tone (반말/존댓말) that fits the speaker and situation. "
        + "Keep names, brands and titles recognizable.\n"
        + "Reply with only the Korean translation of the line inside <line>, with no quotes, notes or explanations.";

    private final Handler main;
    private final StatusListener status;
    // 문장 순서대로 번역되도록 1개 스레드 (문맥 일관성, 요청 폭주 방지)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LlmTranslator");
        t.setDaemon(true);
        return t;
    });

    private AnthropicClient client;
    private String clientKey;
    private volatile boolean disabledForSession = false;
    private volatile boolean closed = false;
    /** 서버가 fallbacks 파라미터를 거부(400)하면 이후 요청에서 빼고 보냄 */
    private volatile boolean useFallbacks = true;

    LlmTranslator(Handler mainHandler, StatusListener status) {
        this.main = mainHandler;
        this.status = status;
    }

    /** 키를 다시 저장했을 때 등: 비활성화 해제 */
    void reset() {
        disabledForSession = false;
    }

    boolean isUsable(String apiKey) {
        return !closed && !disabledForSession && apiKey != null && !apiKey.isEmpty();
    }

    /**
     * @param line    번역할 문장
     * @param context 바로 앞 문장들 (오래된 것 → 최근 순서)
     */
    void translate(String apiKey, String line, List<String> context, Callback cb) {
        if (!isUsable(apiKey)) {
            main.post(() -> cb.onResult(null));
            return;
        }
        executor.execute(() -> {
            String ko = null;
            try {
                try {
                    ko = request(apiKey, line, context);
                } catch (com.anthropic.errors.BadRequestException e) {
                    if (!useFallbacks) throw e;
                    Log.w(TAG, "fallbacks 없이 재시도", e);
                    useFallbacks = false;
                    ko = request(apiKey, line, context);
                }
            } catch (UnauthorizedException | PermissionDeniedException e) {
                disabledForSession = true;
                Log.e(TAG, "Claude API 키 오류", e);
                main.post(() -> status.onStatus(
                    "❌ Claude API 키가 올바르지 않습니다\n✨ AI 번역을 끄고 기본 번역으로 표시합니다 (⚙️ 설정에서 키 확인)"));
            } catch (AnthropicServiceException e) {
                Log.w(TAG, "Claude 번역 실패 (" + e.statusCode() + ")", e);
                if (e.statusCode() == 400 || e.statusCode() == 402) {
                    // 크레딧 부족 등 반복해도 소용없는 오류 → 이번 실행 동안 중지
                    disabledForSession = true;
                    final String msg = e.getMessage();
                    main.post(() -> status.onStatus("⚠️ Claude 번역을 사용할 수 없습니다 (" + msg
                        + ")\n기본 번역으로 표시합니다"));
                }
            } catch (Exception e) {
                Log.w(TAG, "Claude 번역 실패 (네트워크 등)", e); // 이 문장은 기본 번역 유지
            }
            final String result = ko;
            main.post(() -> { if (!closed) cb.onResult(result); });
        });
    }

    void close() {
        closed = true;
        executor.shutdownNow();
    }

    // ── 내부 (LlmTranslator 스레드) ──────────────────────────────────────────

    private String request(String apiKey, String line, List<String> context) {
        AnthropicClient c = client(apiKey);

        StringBuilder user = new StringBuilder();
        if (context != null && !context.isEmpty()) {
            user.append("<earlier_lines>\n");
            for (String s : context) user.append(s).append('\n');
            user.append("</earlier_lines>\n");
        }
        user.append("<line>").append(line).append("</line>");

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(1024L)
            .system(SYSTEM_PROMPT)
            .addUserMessage(user.toString())
            // 실시간 자막 번역: 깊은 추론보다 빠른 응답이 중요 → effort low
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build());
        if (useFallbacks) {
            // 드물게 안전 분류기가 거절하면 서버가 다른 모델로 자동 재시도
            builder.addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        MessageCreateParams params = builder.build();

        BetaMessage msg = c.beta().messages().create(params);
        if (msg.stopReason().map(r -> r.equals(BetaStopReason.REFUSAL)).orElse(false)) {
            return null; // 거절 → 기본 번역 유지
        }
        StringBuilder out = new StringBuilder();
        for (BetaContentBlock b : msg.content()) {
            b.text().ifPresent(t -> out.append(t.text()));
        }
        String ko = out.toString().trim();
        return ko.isEmpty() ? null : ko;
    }

    private synchronized AnthropicClient client(String apiKey) {
        if (client == null || !apiKey.equals(clientKey)) {
            client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
            clientKey = apiKey;
        }
        return client;
    }
}
