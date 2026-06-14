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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.suboverlay.R;
import com.suboverlay.ui.MainActivity;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SubtitleOverlayService extends Service {

    public static final String ACTION_UPDATE_SETTINGS = "com.suboverlay.UPDATE_SETTINGS";
    public static boolean isRunning = false;

    private static final String TAG = "SubOverlay";
    private static final String CHANNEL_ID = "subtitle_overlay_channel";
    private static final int SAMPLE_RATE = 16000;
    private static final long HIDE_DELAY_MS = 5000;

    // UI
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvOverlayKo, tvOverlayEn, tvStatus;

    // 오디오 캡처
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean captureRunning = false;

    // MediaProjection (내부 오디오 캡처용)
    private MediaProjection mediaProjection;

    // Vosk 오프라인 STT
    private Model voskModel;
    private Recognizer voskRecognizer;

    // MLKit 번역
    private Translator enKoTranslator;
    private boolean translatorReady = false;

    // 설정
    private int fontSize = 18;
    private int bgAlpha = 180;
    private String langMode = "both";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideSubtitles;

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

        // MediaProjection 토큰 받기 (내부 오디오 캡처용, API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            if (resultCode != -1 && data != null) {
                MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, data);
            }
        }

        createOverlay();
        setStatus("🔄 Vosk 모델 준비 중...");

        // 백그라운드 스레드에서 모델 다운로드 후 초기화
        new Thread(this::loadVoskModel, "VoskModelLoader").start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        captureRunning = false;
        mainHandler.removeCallbacksAndMessages(null);

        if (audioRecord != null) {
            try { audioRecord.stop(); }    catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (voskRecognizer != null) { voskRecognizer.close(); voskRecognizer = null; }
        if (voskModel != null)      { voskModel.close();      voskModel = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
        }
        if (enKoTranslator != null) enKoTranslator.close();
        try { unregisterReceiver(settingsReceiver); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Vosk 모델 로드 ───────────────────────────────────────────────────────

    private static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String MODEL_DIR_NAME = "vosk-model-small-en-us-0.15";

    private void loadVoskModel() {
        File modelDir = new File(getFilesDir(), MODEL_DIR_NAME);

        if (!modelDir.exists()) {
            setStatus("📥 모델 다운로드 중... (약 40MB)\n잠시 기다려 주세요");
            try {
                File zipFile = new File(getFilesDir(), "vosk-model.zip");
                downloadFile(MODEL_URL, zipFile);
                setStatus("📦 압축 해제 중...");
                unzip(zipFile, getFilesDir());
                zipFile.delete();
            } catch (IOException e) {
                setStatus("❌ 다운로드 실패: " + e.getMessage());
                return;
            }
        }

        try {
            setStatus("⚙️ 모델 로딩 중...");
            voskModel = new Model(modelDir.getAbsolutePath());
            voskRecognizer = new Recognizer(voskModel, SAMPLE_RATE);
            setStatus("✅ 준비 완료! 영상을 재생하세요.");
            startAudioCapture();
        } catch (Exception e) {
            setStatus("❌ 모델 로드 실패: " + e.getMessage());
        }
    }

    private void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.connect();

        int total = conn.getContentLength();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read, downloaded = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                if (total > 0) {
                    int pct = (int) (downloaded * 100L / total);
                    setStatus("📥 다운로드 중... " + pct + "%");
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // ── 오디오 캡처 ──────────────────────────────────────────────────────────

    private void startAudioCapture() {
        int bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            // ✅ 내부 오디오 캡처: 영상 오디오를 직접 받음 → 마이크 X, 오디오 포커스 충돌 X
            audioRecord = buildInternalAudioRecord(bufferSize);
            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                setStatus("⚠️ 내부 오디오 불가 → 마이크로 대체");
                audioRecord = buildMicAudioRecord(bufferSize);
            } else {
                setStatus("🎬 내부 오디오 캡처 중 (영상 방해 없음)");
            }
        } else {
            // 폴백: 마이크 사용 (SpeechRecognizer보다 오디오 포커스 충돌 없음)
            audioRecord = buildMicAudioRecord(bufferSize);
            setStatus("🎤 마이크 캡처 중");
        }

        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("❌ AudioRecord 초기화 실패");
            return;
        }

        audioRecord.startRecording();
        captureRunning = true;

        recordingThread = new Thread(this::captureLoop, "VoskCaptureThread");
        recordingThread.setDaemon(true);
        recordingThread.start();
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private AudioRecord buildInternalAudioRecord(int bufferSize) {
        try {
            AudioPlaybackCaptureConfiguration captureConfig =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

            return new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build();
        } catch (Exception e) {
            Log.e(TAG, "내부 오디오 캡처 생성 실패", e);
            return null;
        }
    }

    private AudioRecord buildMicAudioRecord(int bufferSize) {
        try {
            return new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
        } catch (Exception e) {
            Log.e(TAG, "마이크 AudioRecord 생성 실패", e);
            return null;
        }
    }

    /** 백그라운드 스레드: 오디오 읽어서 Vosk에 전달 */
    private void captureLoop() {
        byte[] buffer = new byte[4096];
        while (captureRunning && !Thread.currentThread().isInterrupted()) {
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);
            if (bytesRead <= 0 || voskRecognizer == null) continue;

            if (voskRecognizer.acceptWaveForm(buffer, bytesRead)) {
                parseAndShow(voskRecognizer.getResult(), false);   // 최종 결과
            } else {
                parseAndShow(voskRecognizer.getPartialResult(), true); // 실시간 중간 결과
            }
        }
    }

    /** Vosk JSON 파싱: {"text":"..."} 또는 {"partial":"..."} */
    private void parseAndShow(String json, boolean isPartial) {
        try {
            JSONObject obj = new JSONObject(json);
            String text = isPartial ? obj.optString("partial") : obj.optString("text");
            if (text != null && !text.trim().isEmpty()) {
                translateAndShow(text.trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON 파싱 실패: " + json);
        }
    }

    // ── MLKit 번역 ────────────────────────────────────────────────────────────

    private void setupTranslator() {
        enKoTranslator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build());
        enKoTranslator.downloadModelIfNeeded()
            .addOnSuccessListener(v -> { translatorReady = true; Log.d(TAG, "번역 모델 OK"); })
            .addOnFailureListener(e -> Log.e(TAG, "번역 모델 실패: " + e.getMessage()));
    }

    private void translateAndShow(String en) {
        showSubtitles(en, null); // 영어 즉시 표시
        if (!translatorReady || "en".equals(langMode)) return;
        enKoTranslator.translate(en)
            .addOnSuccessListener(ko -> showSubtitles(en, ko))
            .addOnFailureListener(e -> Log.e(TAG, "번역 실패: " + e.getMessage()));
    }

    // ── 오버레이 UI ───────────────────────────────────────────────────────────

    private void createOverlay() {
        if (overlayView != null) return;
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_subtitle, null);
        tvOverlayKo = overlayView.findViewById(R.id.tvOverlayKo);
        tvOverlayEn = overlayView.findViewById(R.id.tvOverlayEn);
        tvStatus    = overlayView.findViewById(R.id.tvStatus);

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

    private void showSubtitles(String en, String ko) {
        mainHandler.post(() -> {
            if (!"ko".equals(langMode)) {
                tvOverlayEn.setText(en);
                tvOverlayEn.setVisibility(View.VISIBLE);
            } else {
                tvOverlayEn.setVisibility(View.GONE);
            }
            if (!"en".equals(langMode)) {
                tvOverlayKo.setText(ko != null ? ko : en);
                tvOverlayKo.setVisibility(View.VISIBLE);
            } else {
                tvOverlayKo.setVisibility(View.GONE);
            }
            mainHandler.removeCallbacks(hideRunnable);
            mainHandler.postDelayed(hideRunnable, HIDE_DELAY_MS);
        });
    }

    private void hideSubtitles() {
        if (tvOverlayKo != null) tvOverlayKo.setVisibility(View.GONE);
        if (tvOverlayEn != null) tvOverlayEn.setVisibility(View.GONE);
    }

    private void setStatus(String msg) {
        Log.d(TAG, "STATUS: " + msg);
        mainHandler.post(() -> {
            if (tvStatus != null) {
                tvStatus.setText(msg);
                tvStatus.setVisibility(View.VISIBLE);
            }
        });
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
            .setContentText("Vosk 오프라인 자막 활성화됨")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
