package com.earlln.overlay.service;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Deepgram 실시간 음성 인식 (WebSocket 스트리밍, Nova-3 영어).
 *
 *  - 16kHz mono 16bit PCM 을 그대로 보낸다.
 *  - Deepgram 은 결과를 '조각' 단위로 확정(is_final)하므로, 조각을 이어 붙여 한 발화로 모았다가
 *    발화가 끝나면(speech_final / UtteranceEnd / Finalize) 한 문장으로 전달한다.
 *  - 소리가 없는 동안에는 서비스가 오디오를 보내지 않고 keepAlive() 만 호출한다 (요금 절약).
 *
 * sendAudio/keepAlive/finalizeUtterance/tick 은 캡처 스레드, 콜백은 OkHttp 스레드에서 호출된다.
 */
final class DeepgramStreamer {

    interface Listener {
        void onConnected();
        /** 진행 중인 발화 (지금까지 확정된 조각 + 아직 확정 전 조각) */
        void onPartial(String text);
        /** 발화 완료 */
        void onFinal(String text);
        /** fatal=true 면 재시도해도 소용없는 오류 (API 키 오류 등) */
        void onError(String message, boolean fatal);
    }

    private static final String TAG = "EarllnOverlay";
    private static final String URL = "wss://api.deepgram.com/v1/listen"
        + "?model=nova-3&language=en"
        + "&encoding=linear16&sample_rate=16000&channels=1"
        + "&interim_results=true&smart_format=true&punctuate=true"
        + "&endpointing=300&utterance_end_ms=1000&vad_events=true";

    /** 쉬지 않고 이어지는 발화는 이 단어 수에서 끊어 번역 (긴 입력은 번역/표시 모두 불리) */
    private static final int MAX_UTTERANCE_WORDS = 35;
    /** Finalize 를 보낸 뒤 이 시간 안에 확정 결과가 안 오면 모아둔 발화를 그대로 내보냄 */
    private static final long FINALIZE_FLUSH_MS = 2000;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 스트리밍: 읽기 타임아웃 없음
        .pingInterval(20, TimeUnit.SECONDS)
        .build();

    private final String apiKey;
    private final Listener listener;
    private WebSocket ws;
    private volatile boolean closed = false;

    private final Object lock = new Object();
    private final StringBuilder utterance = new StringBuilder();
    private long flushDeadline = 0;
    private long lastKeepAlive = 0;
    private volatile long bytesSent = 0;

    DeepgramStreamer(String apiKey, Listener listener) {
        this.apiKey = apiKey;
        this.listener = listener;
    }

    void connect() {
        Request req = new Request.Builder()
            .url(URL)
            .header("Authorization", "Token " + apiKey)
            .build();
        ws = CLIENT.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!closed) listener.onConnected();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (!closed) handleMessage(text);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                if (!closed && code != 1000) {
                    listener.onError("연결이 종료됨 (" + code + " " + reason + ")", false);
                }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (closed) return;
                int code = response != null ? response.code() : 0;
                Log.w(TAG, "Deepgram 연결 실패 code=" + code, t);
                if (code == 401 || code == 403) {
                    listener.onError("API 키가 올바르지 않거나 권한이 없습니다", true);
                } else if (code == 402) {
                    listener.onError("Deepgram 크레딧(잔액)이 부족합니다", true);
                } else {
                    listener.onError("인터넷 연결 문제 (" + (code > 0 ? "HTTP " + code : t.getClass().getSimpleName()) + ")", false);
                }
            }
        });
    }

    /** 오디오 전송 (캡처 스레드) */
    void sendAudio(byte[] buf, int n) {
        WebSocket w = ws;
        if (w == null || closed) return;
        w.send(ByteString.of(buf, 0, n));
        bytesSent += n;
    }

    /** 소리가 없어 오디오를 안 보내는 동안 연결 유지 (10초 이상 무소식이면 Deepgram 이 끊음) */
    void keepAlive(long now) {
        WebSocket w = ws;
        if (w == null || closed || now - lastKeepAlive < 5000) return;
        lastKeepAlive = now;
        w.send("{\"type\":\"KeepAlive\"}");
    }

    /** 오디오 전송을 멈출 때 호출: 지금까지 들은 내용을 확정해 달라고 요청 */
    void finalizeUtterance(long now) {
        WebSocket w = ws;
        if (w == null || closed) return;
        w.send("{\"type\":\"Finalize\"}");
        synchronized (lock) {
            if (utterance.length() > 0 || flushDeadline == 0) flushDeadline = now + FINALIZE_FLUSH_MS;
        }
    }

    /** 캡처 루프에서 주기적으로 호출: Finalize 응답이 늦으면 모아둔 발화를 내보냄 */
    void tick(long now) {
        String out = null;
        synchronized (lock) {
            if (flushDeadline > 0 && now >= flushDeadline) {
                flushDeadline = 0;
                out = takeUtterance();
            }
        }
        if (out != null) listener.onFinal(out);
    }

    /** 이 연결로 보낸 오디오 길이 (초) — 요금 참고용 */
    double sentSeconds() {
        return bytesSent / 32000.0; // 16000Hz * 2byte
    }

    void close() {
        closed = true;
        WebSocket w = ws;
        ws = null;
        if (w == null) return;
        try {
            w.send("{\"type\":\"CloseStream\"}");
            w.close(1000, null);
        } catch (Exception ignored) {}
    }

    // ── 응답 처리 (OkHttp 스레드) ───────────────────────────────────────────

    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            if ("UtteranceEnd".equals(type)) {
                emitFinal();
                return;
            }
            if (!"Results".equals(type)) return;

            JSONObject channel = msg.optJSONObject("channel");
            JSONArray alts = channel != null ? channel.optJSONArray("alternatives") : null;
            String transcript = alts != null && alts.length() > 0
                ? alts.getJSONObject(0).optString("transcript", "").trim() : "";
            boolean isFinal = msg.optBoolean("is_final");
            boolean speechFinal = msg.optBoolean("speech_final");
            boolean fromFinalize = msg.optBoolean("from_finalize");

            if (isFinal) {
                String out = null;
                String partial = null;
                synchronized (lock) {
                    if (!transcript.isEmpty()) {
                        if (utterance.length() > 0) utterance.append(' ');
                        utterance.append(transcript);
                    }
                    boolean tooLong = TextNormalizer.wordCount(utterance.toString()) > MAX_UTTERANCE_WORDS;
                    if (speechFinal || fromFinalize || tooLong) {
                        flushDeadline = 0;
                        out = takeUtterance();
                    } else if (utterance.length() > 0) {
                        partial = utterance.toString();
                    }
                }
                if (out != null) listener.onFinal(out);
                else if (partial != null) listener.onPartial(partial);
            } else if (!transcript.isEmpty()) {
                String partial;
                synchronized (lock) {
                    partial = utterance.length() > 0 ? utterance + " " + transcript : transcript;
                }
                listener.onPartial(partial);
            }
        } catch (Exception e) {
            Log.w(TAG, "Deepgram 응답 파싱 실패: " + text, e);
        }
    }

    private void emitFinal() {
        String out;
        synchronized (lock) {
            flushDeadline = 0;
            out = takeUtterance();
        }
        if (out != null) listener.onFinal(out);
    }

    /** lock 안에서 호출 */
    private String takeUtterance() {
        if (utterance.length() == 0) return null;
        String s = utterance.toString().trim();
        utterance.setLength(0);
        return s.isEmpty() ? null : s;
    }
}
