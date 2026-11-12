package com.suboverlay.service;

import android.os.Handler;
import android.util.Log;
import android.util.LruCache;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/**
 * MLKit 영→한 번역 래퍼. 모든 메서드는 메인 스레드에서 호출한다.
 *
 *  - 순서 보장: 요청마다 증가하는 번호를 붙이고, 이미 표시된 것보다 오래된 결과는 버린다
 *  - 부분 결과 디바운스: 인식이 잠시 안정되면(또는 최대 대기 시간 경과 시) 최신 문장만 번역
 *  - 동시 요청 1개 제한: 번역 중 새 부분 결과가 오면 끝난 뒤 최신 것만 번역 (큐 폭발 방지)
 *  - LRU 캐시: 반복되는 문장은 즉시 표시
 *  - 모델 다운로드 실패 시 백오프 재시도, 번역 연속 실패 시 클라이언트 재생성
 */
public final class TranslationEngine {

    public interface Listener {
        /** 번역 결과 (표시 순서가 보장된 것만 전달됨) */
        void onTranslated(String ko);
        void onStatus(String msg);
    }

    private static final String TAG = "SubOverlay";

    private static final long PARTIAL_DEBOUNCE_MS = 400;
    private static final long PARTIAL_MAX_WAIT_MS = 1200;
    private static final int PARTIAL_MIN_WORDS = 3;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000, 30_000, 60_000};

    private final Handler handler;
    private final Listener listener;
    private final LruCache<String, String> cache = new LruCache<>(128);

    private Translator translator;
    private boolean ready = false;
    private boolean closed = false;
    private int downloadAttempt = 0;
    private int consecutiveFailures = 0;

    // 순서 보장
    private long nextSeq = 1;
    private long lastShownSeq = 0;

    // 부분 결과 디바운스/동시성 제어
    private String pendingPartial = null;
    private long pendingPartialSince = 0;
    private boolean partialInFlight = false;
    private long lastPartialTranslateAt = 0;
    private String lastPartialTranslated = null;

    private final Runnable partialRunnable = this::flushPartial;
    private final Runnable retryDownloadRunnable = this::ensureModel;

    public TranslationEngine(Handler mainHandler, Listener listener) {
        this.handler = mainHandler;
        this.listener = listener;
        createClient();
        ensureModel();
    }

    public boolean isReady() {
        return ready;
    }

    private void createClient() {
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
        }
        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build());
    }

    private void ensureModel() {
        if (closed) return;
        final Translator t = translator;
        t.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(v -> {
                if (closed || t != translator) return;
                ready = true;
                downloadAttempt = 0;
                consecutiveFailures = 0;
                Log.d(TAG, "번역 모델 준비 완료");
            })
            .addOnFailureListener(e -> {
                if (closed || t != translator) return;
                ready = false;
                long delay = RETRY_DELAYS_MS[Math.min(downloadAttempt, RETRY_DELAYS_MS.length - 1)];
                downloadAttempt++;
                Log.e(TAG, "번역 모델 다운로드 실패: " + e.getMessage());
                listener.onStatus("⚠️ 번역 모델 다운로드 실패 (네트워크 확인)\n"
                    + (delay / 1000) + "초 후 재시도합니다");
                handler.removeCallbacks(retryDownloadRunnable);
                handler.postDelayed(retryDownloadRunnable, delay);
            });
    }

    /** 최종 인식 결과 번역 (항상 수행) */
    public void translateFinal(String rawEn) {
        // 진행 중이던 부분 번역 예약은 이 최종 결과가 대체한다
        handler.removeCallbacks(partialRunnable);
        pendingPartial = null;
        lastPartialTranslated = null;

        String src = TextNormalizer.forTranslation(rawEn, true);
        if (src.isEmpty()) return;
        request(src, false);
    }

    /** 부분 인식 결과 번역 요청 (디바운스됨) */
    public void submitPartial(String rawEn) {
        if (closed) return;
        String src = TextNormalizer.forTranslation(rawEn, false);
        if (TextNormalizer.wordCount(src) < PARTIAL_MIN_WORDS) return;
        if (src.equals(pendingPartial) || src.equals(lastPartialTranslated)) return;

        long now = System.currentTimeMillis();
        if (pendingPartial == null) pendingPartialSince = now;
        pendingPartial = src;

        handler.removeCallbacks(partialRunnable);
        // 계속 말하는 중이라 안정되지 않아도 최대 대기 시간이 지나면 번역
        long waitedSinceLast = now - Math.max(lastPartialTranslateAt, pendingPartialSince);
        long delay = waitedSinceLast >= PARTIAL_MAX_WAIT_MS ? 0 : PARTIAL_DEBOUNCE_MS;
        handler.postDelayed(partialRunnable, delay);
    }

    private void flushPartial() {
        if (closed || pendingPartial == null) return;
        if (partialInFlight) return; // 끝나면 다시 flush 됨
        String src = pendingPartial;
        pendingPartial = null;
        lastPartialTranslated = src;
        lastPartialTranslateAt = System.currentTimeMillis();
        request(src, true);
    }

    private void request(String src, boolean partial) {
        if (closed) return;
        final long seq = nextSeq++;

        String cached = cache.get(src);
        if (cached != null) {
            deliver(seq, cached);
            return;
        }
        if (!ready) return;

        if (partial) partialInFlight = true;
        final Translator t = translator;
        t.translate(src)
            .addOnSuccessListener(ko -> {
                if (closed) return;
                consecutiveFailures = 0;
                if (ko != null && !ko.trim().isEmpty()) {
                    cache.put(src, ko.trim());
                    deliver(seq, ko.trim());
                }
            })
            .addOnFailureListener(e -> {
                if (closed) return;
                Log.e(TAG, "번역 실패: " + e.getMessage());
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && t == translator) {
                    // 내부 상태가 꼬인 경우를 대비해 클라이언트를 새로 만든다
                    Log.w(TAG, "번역 연속 실패 → 번역기 재생성");
                    consecutiveFailures = 0;
                    ready = false;
                    createClient();
                    ensureModel();
                }
            })
            .addOnCompleteListener(task -> {
                if (closed || !partial) return;
                partialInFlight = false;
                if (pendingPartial != null) handler.post(partialRunnable);
            });
    }

    private void deliver(long seq, String ko) {
        if (seq <= lastShownSeq) return; // 더 최신 결과가 이미 표시됨
        lastShownSeq = seq;
        listener.onTranslated(ko);
    }

    /** 새 세션(오디오 소스 변경 등) 시작 시 대기 중인 부분 번역 폐기 */
    public void resetPending() {
        handler.removeCallbacks(partialRunnable);
        pendingPartial = null;
        lastPartialTranslated = null;
        // 진행 중인 요청 결과는 모두 무시되도록 번호를 건너뛴다
        lastShownSeq = nextSeq++;
    }

    public void close() {
        closed = true;
        handler.removeCallbacks(partialRunnable);
        handler.removeCallbacks(retryDownloadRunnable);
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
            translator = null;
        }
    }
}
