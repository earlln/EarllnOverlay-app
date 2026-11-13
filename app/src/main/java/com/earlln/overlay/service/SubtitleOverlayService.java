package com.earlln.overlay.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
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

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.earlln.overlay.R;
import com.earlln.overlay.note.NoteEntry;
import com.earlln.overlay.note.NotePanelController;
import com.earlln.overlay.ui.MainActivity;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 오디오 캡처 → Vosk STT → MLKit 번역 → '📝 자막 노트' 오버레이 패널.
 *
 * 스레드 구조
 *  - main      : UI, 번역(TranslationEngine), 설정 수신
 *  - control   : 모델 다운로드/로드, 캡처 시작/중지/재시작 (단일 스레드 → 생명주기 작업이 서로 겹치지 않음)
 *  - capture   : AudioRecord.read + Recognizer (recLock 으로 close 와 상호 배제 → 네이티브 크래시 방지)
 */
public class SubtitleOverlayService extends Service {

    public static final String ACTION_UPDATE_SETTINGS = "com.earlln.overlay.UPDATE_SETTINGS";
    /** 알림의 '종료' 버튼 */
    public static final String ACTION_STOP = "com.earlln.overlay.STOP";
    public static final String EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode";
    public static final String EXTRA_PROJECTION_DATA = "projectionData";

    public static volatile boolean isRunning = false;

    private static final String TAG = "EarllnOverlay";
    private static final String CHANNEL_ID = "subtitle_overlay_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 16000;

    /** 쉬지 않고 말할 때 문장을 강제로 끊는 기준 (긴 입력은 번역 품질과 표시가 모두 나빠짐) */
    private static final long MAX_SEGMENT_MS = 12_000;
    private static final int MAX_SEGMENT_WORDS = 35;

    /** 영상이 재생 중인데 내부 오디오가 이 시간 동안 완전 무음이면 캡처가 막힌 것으로 판단 → 마이크 전환 */
    private static final long BLOCKED_DETECT_MS = 4_000;
    /** 이 시간 동안 들어오는 소리가 없고 재생 중인 미디어도 없으면 '영상 앱 음소거 해제' 안내 */
    private static final long NO_AUDIO_HINT_MS = 8_000;
    /** 이 값 이상의 진폭이 있으면 '소리 있음' (16bit 기준, 약 -40dBFS) */
    private static final int SOUND_PEAK_THRESHOLD = 300;

    /** 오디오 장치 오류 시 자동 복구 제한 */
    private static final int MAX_AUTO_RESTARTS = 5;
    private static final long AUTO_RESTART_WINDOW_MS = 60_000;

    private static final int EN_PARTIAL_MAX_CHARS = 160;

    // ── UI (main) ──
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotePanelController notePanel;

    // ── 설정 ──
    private int fontSize = 18;
    private int bgAlpha = 180;
    private volatile String langMode = "both";
    private volatile String audioSource = "internal";
    private volatile String sttModelKey = "small";
    private volatile boolean bottomSubtitle = true;

    // ── 생명주기 / 스레드 ──
    private volatile boolean destroyed = false;
    private boolean pipelineStarted = false;
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EarllnOverlayControl");
        t.setDaemon(true);
        return t;
    });

    // ── 오디오 캡처 (control 스레드에서만 변경) ──
    private volatile AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean captureRunning = false;
    private volatile int captureSession = 0;
    private int autoRestartCount = 0;
    private long autoRestartWindowStart = 0;

    // ── MediaProjection ──
    private volatile MediaProjection mediaProjection;
    /** 재생 앱이 내부 오디오 캡처를 막은 것으로 판단됨 → 마이크 사용 (소스 변경/새 권한 시 초기화) */
    private volatile boolean internalBlocked = false;
    private AudioManager audioManager;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.w(TAG, "MediaProjection 종료됨 (사용자가 화면 공유를 중지)");
            mediaProjection = null;
            if (!"mic".equals(audioSource)) {
                setStatus("⚠️ 화면 공유가 중지되어 마이크로 전환합니다");
                runOnControl(() -> restartCapture(captureSession));
            }
        }
    };

    // ── Vosk (control 스레드에서 생성/해제, capture 스레드에서 사용) ──
    private final Object recLock = new Object();
    private Model voskModel;
    private VoskModelManager.ModelSpec loadedSpec;
    private Recognizer recognizer;

    // ── 번역 (main) ──
    private TranslationEngine translationEngine;

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyIntentSettings(intent, true);
        }
    };

    // ── 생명주기 ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        notePanel = new NotePanelController(this, mainHandler, this::stopSelf);
        audioManager = getSystemService(AudioManager.class);
        createNotificationChannel();
        registerReceiver(settingsReceiver,
            new IntentFilter(ACTION_UPDATE_SETTINGS), Context.RECEIVER_NOT_EXPORTED);
        translationEngine = new TranslationEngine(mainHandler, new TranslationEngine.Listener() {
            @Override public void onPartialTranslated(String ko) {
                if (!destroyed && !"en".equals(langMode)) notePanel.setLiveKo(ko);
            }
            @Override public void onStatus(String msg) { setStatus(msg); }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); // 알림의 '종료' 버튼
            return START_NOT_STICKY;
        }
        boolean hasProjectionData = intent != null && intent.hasExtra(EXTRA_PROJECTION_DATA);
        startForegroundSafely(hasProjectionData);

        if (intent == null) {
            // 시스템 재시작 등으로 정보 없이 호출된 경우: 계속 실행할 근거가 없으므로 종료
            stopSelf();
            return START_NOT_STICKY;
        }

        applyIntentSettings(intent, pipelineStarted);

        if (hasProjectionData) {
            MediaProjection newProjection = createProjection(intent);
            if (newProjection != null) {
                MediaProjection old = mediaProjection;
                mediaProjection = newProjection;
                internalBlocked = false;
                if (old != null) {
                    try { old.unregisterCallback(projectionCallback); old.stop(); }
                    catch (Exception ignored) {}
                }
                if (pipelineStarted) runOnControl(() -> restartCapture(captureSession));
            }
        }

        if (!notePanel.show()) {
            // 오버레이 권한이 취소된 경우 등
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!pipelineStarted) {
            pipelineStarted = true;
            setStatus("🔄 준비 중...");
            runOnControl(this::initPipeline);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        isRunning = false;
        captureRunning = false;
        mainHandler.removeCallbacksAndMessages(null);

        // read() 블로킹을 즉시 풀기 위해 먼저 stop
        AudioRecord ar = audioRecord;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
        }

        // 네이티브 자원 해제는 캡처 스레드 종료를 기다린 뒤 control 스레드에서 수행
        try {
            control.execute(() -> {
                stopCapture();
                releaseModel();
            });
        } catch (RejectedExecutionException ignored) {}
        control.shutdown();

        MediaProjection mp = mediaProjection;
        mediaProjection = null;
        if (mp != null) {
            try { mp.unregisterCallback(projectionCallback); mp.stop(); } catch (Exception ignored) {}
        }
        if (translationEngine != null) translationEngine.close();
        if (notePanel != null) notePanel.destroy(); // 기록도 함께 삭제됨
        try { unregisterReceiver(settingsReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (notePanel != null) notePanel.onConfigurationChanged();
    }

    /** 앱을 최근 앱에서 스와이프로 종료할 때 서비스도 함께 종료 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    private void startForegroundSafely(boolean withProjection) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                if (withProjection) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(NOTIFICATION_ID, n, type);
                return;
            } catch (SecurityException e) {
                // 화면 캡처 동의가 무효한 경우 등 → 마이크 타입만으로 재시도
                Log.w(TAG, "startForeground(mediaProjection) 실패 → 마이크 전용", e);
            }
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /** 포그라운드 서비스(mediaProjection 타입) 시작 이후에 생성해야 SecurityException이 나지 않음 */
    private MediaProjection createProjection(Intent intent) {
        try {
            int resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            if (resultCode != Activity.RESULT_OK || data == null) return null;

            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            MediaProjection mp = mpm.getMediaProjection(resultCode, data);
            if (mp != null) {
                mp.registerCallback(projectionCallback, mainHandler);
                Log.d(TAG, "MediaProjection 생성 성공");
            }
            return mp;
        } catch (Exception e) {
            Log.e(TAG, "MediaProjection 생성 실패 → 마이크 사용", e);
            return null;
        }
    }

    // ── 설정 ─────────────────────────────────────────────────────────────────

    private void applyIntentSettings(Intent intent, boolean reactToChanges) {
        fontSize = intent.getIntExtra("fontSize", fontSize);
        bgAlpha  = intent.getIntExtra("bgAlpha", bgAlpha);

        String newLang = intent.getStringExtra("langMode");
        if (newLang != null && !newLang.equals(langMode)) {
            langMode = newLang;
            if ("en".equals(newLang)) translationEngine.resetPending();
            // "English 전용"일 때 쌓인 문장도 번역이 필요해지면 뒤늦게 번역
            else mainHandler.post(this::translateMissingEntries);
        }

        String newSource = intent.getStringExtra("audioSource");
        boolean sourceChanged = newSource != null && !newSource.equals(audioSource);
        if (newSource != null) audioSource = newSource;
        if (sourceChanged) internalBlocked = false; // 사용자가 다시 고르면 내부 오디오 재시도

        if (intent.hasExtra("bottomSubtitle")) {
            bottomSubtitle = intent.getBooleanExtra("bottomSubtitle", true);
        }

        String newModel = intent.getStringExtra("sttModel");
        boolean modelChanged = newModel != null && !newModel.equals(sttModelKey);
        if (newModel != null) sttModelKey = newModel;

        if (reactToChanges) {
            // control 스레드에서 직렬 처리 → 모델 로딩 중 변경되어도 캡처가 중복 생성되지 않음
            if (modelChanged) {
                runOnControl(this::reloadModelIfNeeded);
            } else if (sourceChanged) {
                runOnControl(() -> restartCapture(captureSession));
            }
        }
        applySettings();
    }

    private void runOnControl(Runnable r) {
        if (destroyed) return;
        try {
            control.execute(() -> {
                if (destroyed) return;
                try {
                    r.run();
                } catch (Exception e) {
                    Log.e(TAG, "control 작업 오류", e);
                    setStatus("❌ 오류: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    // ── 파이프라인 (control 스레드) ────────────────────────────────────────────

    private void initPipeline() {
        // 구버전이 남긴 임시 zip 정리
        //noinspection ResultOfMethodCallIgnored
        new File(getFilesDir(), "vosk-model.zip").delete();
        if (loadModel()) startCapture();
    }

    private void reloadModelIfNeeded() {
        VoskModelManager.ModelSpec want = VoskModelManager.ModelSpec.fromKey(sttModelKey);
        if (want == loadedSpec && voskModel != null) return;
        stopCapture();
        releaseModel();
        if (loadModel()) startCapture();
    }

    /** 모델 설치 확인 → 로드. 로드 실패 시 손상으로 보고 1회 재설치 */
    private boolean loadModel() {
        VoskModelManager.ModelSpec spec = VoskModelManager.ModelSpec.fromKey(sttModelKey);
        VoskModelManager manager = new VoskModelManager(getFilesDir(), this::setStatus, () -> destroyed);

        for (int attempt = 0; attempt < 2; attempt++) {
            File dir;
            try {
                dir = manager.ensureInstalled(spec);
            } catch (IOException e) {
                if (!destroyed) setStatus("❌ STT 모델 다운로드 실패: " + e.getMessage()
                    + "\n네트워크 확인 후 다시 시작해 주세요");
                return false;
            }
            if (destroyed) return false;
            try {
                setStatus("⚙️ STT 모델 로딩 중..." + (spec == VoskModelManager.ModelSpec.LARGE
                    ? "\n(정확도 모델은 시간이 조금 걸립니다)" : ""));
                Model m = new Model(dir.getAbsolutePath());
                if (destroyed) { m.close(); return false; }
                voskModel = m;
                loadedSpec = spec;
                return true;
            } catch (Throwable e) {
                Log.e(TAG, "모델 로드 실패 → 재설치 시도", e);
                manager.delete(spec);
            }
        }
        setStatus("❌ STT 모델 로드 실패 (메모리 부족일 수 있습니다)\n빠른 모델로 바꿔 보세요");
        return false;
    }

    private void releaseModel() {
        if (voskModel != null) {
            try { voskModel.close(); } catch (Exception ignored) {}
            voskModel = null;
            loadedSpec = null;
        }
    }

    private void startCapture() {
        if (destroyed || voskModel == null || captureRunning) return;

        Recognizer rec;
        try {
            rec = new Recognizer(voskModel, SAMPLE_RATE);
        } catch (Exception e) {
            setStatus("❌ 음성 인식기 생성 실패: " + e.getMessage());
            return;
        }

        int minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuf, 4096) * 4;

        boolean internal = false;
        AudioRecord ar = null;
        String statusMsg;
        if ("mic".equals(audioSource)) {
            ar = buildMicAudioRecord(bufferSize);
            statusMsg = "🎤 마이크 캡처 중\n주변 소리를 인식합니다";
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ar = buildMicAudioRecord(bufferSize);
            statusMsg = "🎤 마이크 캡처 중\n(Android 10 미만은 내부 오디오 미지원)";
        } else if (mediaProjection == null) {
            ar = buildMicAudioRecord(bufferSize);
            statusMsg = "🎤 마이크 캡처 중\n(화면 공유 권한이 없어 내부 오디오 불가)";
        } else if (internalBlocked) {
            ar = buildMicAudioRecord(bufferSize);
            statusMsg = "🎤 마이크 캡처 중 (재생 앱이 내부 오디오를 막음)\n볼륨을 켜 두세요";
        } else {
            ar = buildInternalAudioRecord(bufferSize);
            if (isUsable(ar)) {
                internal = true;
                statusMsg = "📱 내부 오디오 캡처 중\n볼륨 0이어도 인식 가능";
            } else {
                releaseQuietly(ar);
                ar = buildMicAudioRecord(bufferSize);
                statusMsg = "⚠️ 내부 오디오 불가 → 🎤 마이크로 자동 전환";
            }
        }

        if (!isUsable(ar)) {
            releaseQuietly(ar);
            rec.close();
            setStatus("❌ 오디오 녹음 초기화 실패\n다른 앱이 마이크를 사용 중인지 확인해 주세요");
            return;
        }

        try {
            ar.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "startRecording 실패", e);
        }
        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            releaseQuietly(ar);
            rec.close();
            setStatus("❌ 녹음 시작 실패\n다른 앱이 마이크를 사용 중일 수 있습니다");
            return;
        }

        synchronized (recLock) { recognizer = rec; }
        audioRecord = ar;
        captureRunning = true;
        final int session = ++captureSession;
        final boolean isInternal = internal;
        final AudioRecord arFinal = ar;
        final Recognizer recFinal = rec;
        captureThread = new Thread(() -> captureLoop(session, arFinal, recFinal, isInternal), "VoskCapture");
        captureThread.setDaemon(true);
        captureThread.start();

        setStatus(statusMsg + (translationEngine.isReady() ? "" : "\n(번역 모델 준비 중...)"));
        mainHandler.post(() -> translationEngine.resetPending());
    }

    private void stopCapture() {
        captureRunning = false;
        AudioRecord ar = audioRecord;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
        }
        Thread t = captureThread;
        if (t != null) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (ar != null) {
            releaseQuietly(ar);
            audioRecord = null;
        }
        // 캡처 스레드는 recLock 안에서 captureRunning 을 다시 확인하므로, 여기서 닫아도 안전
        synchronized (recLock) {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) {}
                recognizer = null;
            }
        }
    }

    /** session 이 여전히 현재 세션일 때만 재시작 (오래된 복구 요청 무시) */
    private void restartCapture(int session) {
        if (session != captureSession) return;
        stopCapture();
        startCapture();
    }

    private static boolean isUsable(AudioRecord ar) {
        return ar != null && ar.getState() == AudioRecord.STATE_INITIALIZED;
    }

    private static void releaseQuietly(AudioRecord ar) {
        if (ar == null) return;
        try { ar.release(); } catch (Exception ignored) {}
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

    // ── 캡처 루프 (capture 스레드) ────────────────────────────────────────────

    private void captureLoop(int session, AudioRecord ar, Recognizer rec, boolean internal) {
        byte[] buffer = new byte[4096]; // 16kHz mono 16bit 기준 128ms
        String lastPartial = "";
        long segmentStart = 0;
        long zeroSince = System.currentTimeMillis();
        long lastMusicCheck = 0;
        long lastLevelReport = 0;
        boolean soundInWindow = false;
        long quietSince = System.currentTimeMillis();
        boolean quietHinted = false;
        boolean fallbackRequested = false;
        boolean deviceError = false;

        while (captureRunning && session == captureSession) {
            int n;
            try {
                n = ar.read(buffer, 0, buffer.length);
            } catch (Exception e) {
                n = -1;
            }
            if (n < 0) {
                deviceError = captureRunning; // 의도적 중지가 아니면 장치 오류
                Log.w(TAG, "AudioRecord.read 오류 코드: " + n);
                break;
            }
            if (n == 0) continue;

            long now = System.currentTimeMillis();
            int peak = peakAmplitude(buffer, n);

            // 패널 상태 줄에 '소리 감지됨/없음' 표시 (약 1초마다)
            soundInWindow |= peak >= SOUND_PEAK_THRESHOLD;
            if (now - lastLevelReport >= 1000) {
                lastLevelReport = now;
                final boolean snd = soundInWindow;
                soundInWindow = false;
                mainHandler.post(() -> { if (!destroyed) notePanel.setAudioLevel(snd); });
            }

            // 한동안 소리가 전혀 없고 시스템에도 재생 중인 미디어가 없으면 → 영상 앱 음소거 안내 (1회)
            if (peak >= SOUND_PEAK_THRESHOLD) {
                quietSince = now;
                quietHinted = false;
            } else if (!quietHinted && now - quietSince >= NO_AUDIO_HINT_MS) {
                quietHinted = true;
                if (audioManager == null || !audioManager.isMusicActive()) {
                    setStatus(internal
                        ? "🔇 들어오는 소리가 없습니다\n영상 앱의 음소거(🔇)를 해제해 주세요 (휴대폰 볼륨은 0이어도 됩니다)"
                        : "🔇 들어오는 소리가 없습니다\n마이크 모드는 휴대폰 볼륨을 켜 두어야 합니다");
                }
            }

            // 내부 오디오가 '완전한 0'(디지털 무음)인데 시스템상 미디어가 재생 중이면 → 캡처 차단으로 판단
            if (internal && !fallbackRequested) {
                if (peak > 0) {
                    zeroSince = now;
                } else if (now - zeroSince >= BLOCKED_DETECT_MS && now - lastMusicCheck >= 500) {
                    lastMusicCheck = now;
                    if (audioManager != null && audioManager.isMusicActive()) {
                        fallbackRequested = true;
                        internalBlocked = true;
                        Log.w(TAG, "내부 오디오 캡처 차단 감지 → 마이크로 전환");
                        setStatus("⚠️ 재생 중인 앱이 내부 오디오 캡처를 막고 있어\n🎤 마이크로 자동 전환합니다");
                        final int s = session;
                        runOnControl(() -> restartCapture(s));
                    }
                }
            }

            String finalJson = null;
            String partialJson = null;
            synchronized (recLock) {
                if (!captureRunning || recognizer != rec) break; // 이미 해제됨
                if (rec.acceptWaveForm(buffer, n)) {
                    finalJson = rec.getResult();
                } else {
                    partialJson = rec.getPartialResult();
                }
            }

            if (partialJson != null) {
                String partial = extract(partialJson, "partial");
                if (partial.isEmpty() || partial.equals(lastPartial)) continue;
                lastPartial = partial;
                now = System.currentTimeMillis();
                if (segmentStart == 0) segmentStart = now;

                // 계속 이어지는 발화는 강제로 끊어서 번역 → 지연/품질 개선
                if (now - segmentStart > MAX_SEGMENT_MS
                        || TextNormalizer.wordCount(partial) > MAX_SEGMENT_WORDS) {
                    synchronized (recLock) {
                        if (!captureRunning || recognizer != rec) break;
                        finalJson = rec.getFinalResult(); // 결과 반환 + 내부 상태 리셋
                    }
                } else {
                    final String p = partial;
                    mainHandler.post(() -> onPartialResult(p));
                    continue;
                }
            }

            if (finalJson != null) {
                lastPartial = "";
                segmentStart = 0;
                String text = extract(finalJson, "text");
                if (!TextNormalizer.isNoise(text)) {
                    mainHandler.post(() -> onFinalResult(text));
                }
            }
        }
        Log.d(TAG, "captureLoop 종료 (session " + session + ")");

        if (deviceError && !destroyed) scheduleAutoRestart(session);
    }

    /** 16bit little-endian PCM 의 최대 진폭 */
    private static int peakAmplitude(byte[] buf, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
            if (v > peak) peak = v;
        }
        return peak;
    }

    private static String extract(String json, String key) {
        try {
            String s = new JSONObject(json).optString(key, "");
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            Log.w(TAG, "JSON 파싱 실패: " + json);
            return "";
        }
    }

    /** 오디오 서버 재시작 등으로 녹음이 끊기면 자동 복구 (폭주 방지 제한 포함) */
    private void scheduleAutoRestart(int session) {
        long now = System.currentTimeMillis();
        mainHandler.post(() -> {
            if (now - autoRestartWindowStart > AUTO_RESTART_WINDOW_MS) {
                autoRestartWindowStart = now;
                autoRestartCount = 0;
            }
            if (++autoRestartCount > MAX_AUTO_RESTARTS) {
                setStatus("❌ 오디오 장치 오류가 반복됩니다\n자막을 중지 후 다시 시작해 주세요");
                return;
            }
            setStatus("⚠️ 오디오 끊김 감지 → 자동 복구 중...");
            mainHandler.postDelayed(() -> runOnControl(() -> restartCapture(session)), 1000);
        });
    }

    // ── 인식 결과 처리 (main) ─────────────────────────────────────────────────

    private void onPartialResult(String raw) {
        if (destroyed) return;
        String display = TextNormalizer.tail(TextNormalizer.forTranslation(raw, false), EN_PARTIAL_MAX_CHARS);
        if (display == null || display.isEmpty()) return;
        notePanel.setLive(display);
        if (!"en".equals(langMode)) translationEngine.submitPartial(raw);
    }

    private void onFinalResult(String raw) {
        if (destroyed) return;
        String sentence = TextNormalizer.forTranslation(raw, true);
        if (sentence.isEmpty()) return;
        notePanel.clearLive();
        NoteEntry entry = notePanel.addEntry(sentence);
        if (!"en".equals(langMode)) requestTranslation(entry);
    }

    /** 문장 하나 번역 → 해당 문장에 결과를 붙인다 */
    private void requestTranslation(NoteEntry entry) {
        entry.koState = NoteEntry.KoState.PENDING;
        notePanel.onEntryUpdated(entry);
        translationEngine.translateFinal(entry.en, ko -> {
            if (destroyed) return;
            entry.ko = ko;
            entry.koState = ko != null ? NoteEntry.KoState.DONE : NoteEntry.KoState.FAILED;
            notePanel.onEntryUpdated(entry);
        });
    }

    private void translateMissingEntries() {
        if (destroyed || "en".equals(langMode)) return;
        for (NoteEntry e : notePanel.entries()) {
            if (e.koState == NoteEntry.KoState.NONE) requestTranslation(e);
        }
    }

    // ── 오버레이 UI (main) ────────────────────────────────────────────────────

    private void applySettings() {
        mainHandler.post(() -> {
            if (!destroyed) notePanel.applySettings(fontSize, bgAlpha, langMode, bottomSubtitle);
        });
    }

    /** 상태 안내: 플로팅 버튼 아래 작은 라벨에 표시 (어느 스레드에서나 호출 가능) */
    private void setStatus(String msg) {
        Log.d(TAG, "STATUS: " + msg);
        mainHandler.post(() -> {
            if (!destroyed) notePanel.setStatus(msg);
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
            .setContentTitle("EarllnOverlay 실행 중")
            .setContentText("📝 자막 노트 실행 중 · 화면 위 버튼으로 열기")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .addAction(0, "종료", PendingIntent.getService(this, 1,
                new Intent(this, SubtitleOverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build();
    }
}
