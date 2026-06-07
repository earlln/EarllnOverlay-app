package com.suboverlay.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.suboverlay.R;
import com.suboverlay.ui.MainActivity;

import java.util.ArrayList;
import java.util.Locale;

public class SubtitleOverlayService extends Service {

    public static final String ACTION_UPDATE_SETTINGS = "com.suboverlay.UPDATE_SETTINGS";
    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "subtitle_overlay_channel";
    private static final String TAG = "SubOverlay";

    // STT 오류 코드 → 설명
    private static String sttErrorMsg(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:               return "오류: 오디오 녹음 실패";
            case SpeechRecognizer.ERROR_CLIENT:              return "오류: 클라이언트 오류";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "오류: 마이크 권한 없음!";
            case SpeechRecognizer.ERROR_NETWORK:             return "오류: 네트워크 없음";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:     return "오류: 네트워크 타임아웃";
            case SpeechRecognizer.ERROR_NO_MATCH:            return "소리 없음(재시작)";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:     return "인식기 바쁨(재시작)";
            case SpeechRecognizer.ERROR_SERVER:              return "오류: 서버 오류";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:      return "침묵 감지(재시작)";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "오류: 영어 STT 미지원";
            default:                                          return "오류 코드: " + code;
        }
    }

    // UI
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvOverlayKo, tvOverlayEn, tvStatus;

    // STT
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;
    private boolean shouldRestart = true;
    private int restartCount = 0;

    // MLKit 번역기
    private Translator enKoTranslator;
    private boolean translatorReady = false;

    // 설정
    private int fontSize = 18;
    private int bgAlpha = 180;
    private String langMode = "both";

    private static final long HIDE_DELAY_MS = 4000;
    private final Runnable hideSubtitleRunnable = this::hideSubtitles;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            fontSize = intent.getIntExtra("fontSize", 18);
            bgAlpha  = intent.getIntExtra("bgAlpha", 180);
            langMode = intent.getStringExtra("langMode");
            if (langMode == null) langMode = "both";
            applySettings();
        }
    };

    // ── 생명주기 ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        registerReceiver(settingsReceiver,
            new IntentFilter(ACTION_UPDATE_SETTINGS), Context.RECEIVER_NOT_EXPORTED);
        setupTranslator();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        startForeground(1, buildNotification());

        fontSize = intent.getIntExtra("fontSize", 18);
        bgAlpha  = intent.getIntExtra("bgAlpha", 180);
        langMode = intent.getStringExtra("langMode");
        if (langMode == null) langMode = "both";

        createOverlay();
        setStatus("STT 초기화 중...");

        // SpeechRecognizer 가용 여부 먼저 체크
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("❌ Google STT 사용 불가\nGoogle 앱 업데이트 필요");
            return START_NOT_STICKY;
        }

        buildRecognizerIntent();
        createAndStartRecognizer();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning     = false;
        shouldRestart = false;
        mainHandler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
        if (enKoTranslator != null) enKoTranslator.close();
        try { unregisterReceiver(settingsReceiver); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── 오버레이 ──────────────────────────────────────────────────────────────

    private void createOverlay() {
        if (overlayView != null) return;

        overlayView  = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null);
        tvOverlayKo  = overlayView.findViewById(R.id.tvOverlayKo);
        tvOverlayEn  = overlayView.findViewById(R.id.tvOverlayEn);
        tvStatus     = overlayView.findViewById(R.id.tvStatus);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 100;

        windowManager.addView(overlayView, params);
        applySettings();
    }

    private void applySettings() {
        if (overlayView == null) return;
        mainHandler.post(() -> {
            tvOverlayKo.setTextSize(fontSize);
            tvOverlayEn.setTextSize(Math.max(12, fontSize - 3));
        });
    }

    // ── 상태 표시 ─────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        Log.d(TAG, "STATUS: " + msg);
        mainHandler.post(() -> {
            if (tvStatus != null) {
                tvStatus.setText(msg);
                tvStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    // ── 자막 표시/숨김 ────────────────────────────────────────────────────────

    private void showSubtitles(String en, String ko) {
        mainHandler.post(() -> {
            if ("en".equals(langMode) || "both".equals(langMode)) {
                tvOverlayEn.setText(en);
                tvOverlayEn.setVisibility(View.VISIBLE);
            } else {
                tvOverlayEn.setVisibility(View.GONE);
            }

            if (("ko".equals(langMode) || "both".equals(langMode)) && ko != null) {
                tvOverlayKo.setText(ko);
                tvOverlayKo.setVisibility(View.VISIBLE);
            } else if ("ko".equals(langMode)) {
                tvOverlayKo.setText(en); // 번역 전 영어라도 표시
                tvOverlayKo.setVisibility(View.VISIBLE);
            }

            mainHandler.removeCallbacks(hideSubtitleRunnable);
            mainHandler.postDelayed(hideSubtitleRunnable, HIDE_DELAY_MS);
        });
    }

    private void hideSubtitles() {
        if (tvOverlayKo != null) tvOverlayKo.setVisibility(View.GONE);
        if (tvOverlayEn != null) tvOverlayEn.setVisibility(View.GONE);
    }

    // ── Google STT ────────────────────────────────────────────────────────────

    private void buildRecognizerIntent() {
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
    }

    private void createAndStartRecognizer() {
        if (!shouldRestart) return;
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle p) {
                isListening = true;
                restartCount = 0;
                setStatus("🎤 듣는 중... (재시작: " + restartCount + ")");
            }

            @Override
            public void onPartialResults(Bundle b) {
                ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && !list.get(0).isEmpty()) {
                    setStatus("🎤 인식 중: " + list.get(0));
                    showSubtitles(list.get(0), null);
                }
            }

            @Override
            public void onResults(Bundle b) {
                ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && !list.get(0).isEmpty()) {
                    String text = list.get(0);
                    setStatus("✅ 인식됨: " + text);
                    translateAndShow(text);
                }
                if (shouldRestart) mainHandler.postDelayed(() -> restartListening(), 200);
            }

            @Override
            public void onError(int error) {
                isListening = false;
                restartCount++;
                String msg = sttErrorMsg(error) + " [" + restartCount + "회]";
                setStatus(msg);
                long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 1500L : 600L;
                if (shouldRestart) mainHandler.postDelayed(() -> restartListening(), delay);
            }

            @Override public void onBeginningOfSpeech() { setStatus("🔊 소리 감지됨"); }
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onEvent(int t, Bundle p) {}
        });

        try {
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
            setStatus("STT 시작 요청...");
        } catch (Exception e) {
            setStatus("❌ STT 시작 예외: " + e.getMessage());
        }
    }

    private void restartListening() {
        if (!shouldRestart || !isRunning) return;
        try {
            if (speechRecognizer != null) {
                speechRecognizer.startListening(recognizerIntent);
                isListening = true;
            } else {
                createAndStartRecognizer();
            }
        } catch (Exception e) {
            setStatus("재시작 실패: " + e.getMessage());
            mainHandler.postDelayed(() -> createAndStartRecognizer(), 1000);
        }
    }

    // ── MLKit 번역 ────────────────────────────────────────────────────────────

    private void setupTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build();
        enKoTranslator = Translation.getClient(options);
        enKoTranslator.downloadModelIfNeeded()
            .addOnSuccessListener(v -> { translatorReady = true; Log.d(TAG, "번역 모델 OK"); })
            .addOnFailureListener(e -> Log.e(TAG, "번역 모델 실패: " + e.getMessage()));
    }

    private void translateAndShow(String en) {
        showSubtitles(en, "ko".equals(langMode) ? en : null);
        if (!translatorReady || "en".equals(langMode)) return;
        enKoTranslator.translate(en)
            .addOnSuccessListener(ko -> showSubtitles(en, ko))
            .addOnFailureListener(e -> Log.e(TAG, "번역 실패: " + e.getMessage()));
    }

    // ── 알림 ─────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "자막 오버레이", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SubOverlay 실행 중")
            .setContentText("실시간 자막 오버레이 활성화됨")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
