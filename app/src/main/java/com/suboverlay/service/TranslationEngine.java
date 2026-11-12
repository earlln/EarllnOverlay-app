package com.suboverlay.service;

import android.os.Handler;
import android.util.Log;
import android.util.LruCache;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayDeque;

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
        /** 부분(진행 중) 문장의 번역. 순서가 보장된 최신 것만 전달됨 */
        void onPartialTranslated(String ko);
        void onStatus(String msg);
    }

    /** 확정 문장 1개의 번역 결과. ko == null 이면 번역 실패 */
    public interface FinalCallback {
        void onResult(String ko);
    }

    private static final class PendingFinal {
        final String src;
        final FinalCallback cb;
        PendingFinal(String src, FinalCallback cb) { this.src = src; this.cb = cb; }
    }

    /** 번역 모델 준비 전에 들어온 확정 문장 (준비되면 순서대로 번역) */
    private static final int MAX_PENDING_FINALS = 100;
    private final ArrayDeque<PendingFinal> pendingFinals = new ArrayDeque<>();

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
                flushPendingFinals();
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

    /** 확정 문장 번역. 결과는 해당 문장의 콜백으로만 전달된다 (순서 무관하게 항상 전달) */
    public void translateFinal(String rawEn, FinalCallback cb) {
        // 진행 중이던 부분 번역은 이 확정 문장이 대체한다 → 늦게 오는 부분 번역은 버림
        handler.removeCallbacks(partialRunnable);
        pendingPartial = null;
        lastPartialTranslated = null;
        lastShownSeq = nextSeq++;

        if (closed) return;
        String src = TextNormalizer.forTranslation(rawEn, true);
        if (src.isEmpty()) return;

        String cached = cache.get(src);
        if (cached != null) {
            cb.onResult(cached);
            return;
        }
        if (!ready) {
            if (pendingFinals.size() >= MAX_PENDING_FINALS) {
                pendingFinals.pollFirst().cb.onResult(null);
            }
            pendingFinals.addLast(new PendingFinal(src, cb));
            return;
        }
        translateFinalNow(src, cb);
    }

    private void flushPendingFinals() {
        while (!pendingFinals.isEmpty() && ready && !closed) {
            PendingFinal p = pendingFinals.pollFirst();
            translateFinalNow(p.src, p.cb);
        }
    }

    private void translateFinalNow(String src, FinalCallback cb) {
        final Translator t = translator;
        t.translate(src)
            .addOnSuccessListener(ko -> {
                if (closed) return;
                consecutiveFailures = 0;
                String v = ko == null ? "" : ko.trim();
                if (v.isEmpty()) { cb.onResult(null); return; }
                cache.put(src, v);
                cb.onResult(v);
            })
            .addOnFailureListener(e -> {
                if (closed) return;
                onTranslateFailure(e, t);
                cb.onResult(null);
            });
    }

    private void onTranslateFailure(Exception e, Translator t) {
        Log.e(TAG, "번역 실패: " + e.getMessage());
        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && t == translator) {
            // 내부 상태가 꼬인 경우를 대비해 클라이언트를 새로 만든다
            Log.w(TAG, "번역 연속 실패 → 번역기 재생성");
            consecutiveFailures = 0;
            ready = false;
            createClient();
            ensureModel();
        }
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
        requestPartial(src);
    }

    private void requestPartial(String src) {
        if (closed) return;
        final long seq = nextSeq++;

        String cached = cache.get(src);
        if (cached != null) {
            deliver(seq, cached);
            return;
        }
        if (!ready) return;

        partialInFlight = true;
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
                onTranslateFailure(e, t);
            })
            .addOnCompleteListener(task -> {
                if (closed) return;
                partialInFlight = false;
                if (pendingPartial != null) handler.post(partialRunnable);
            });
    }

    private void deliver(long seq, String ko) {
        if (seq <= lastShownSeq) return; // 더 최신 결과가 이미 표시됨
        lastShownSeq = seq;
        listener.onPartialTranslated(ko);
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
        pendingFinals.clear();
        handler.removeCallbacks(partialRunnable);
        handler.removeCallbacks(retryDownloadRunnable);
        if (translator != null) {
            try { translator.close(); } catch (Exception ignored) {}
            translator = null;
        }
    }
}
