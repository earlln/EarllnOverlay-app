package com.earlln.overlay.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.earlln.overlay.service.SubtitleOverlayService;

/** 사용자 설정 (SharedPreferences 저장 + 서비스 전달) */
final class AppSettings {

    private static final String PREFS = "settings";

    int fontSize = 18;
    int bgAlpha = 180;
    String langMode = "both";       // both | ko | en
    String audioSource = "internal"; // internal | mic
    String sttModel = "small";       // small | large
    String sttEngine = "online";     // online(Deepgram) | offline(Vosk)
    String translateEngine = "llm";  // llm(Claude) | basic(기기 번역)
    boolean bottomSubtitle = true;

    static AppSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.fontSize = clamp(p.getInt("fontSize", 18), 12, 28);
        int alpha = clamp(p.getInt("bgAlpha", 180), 0, 255);
        s.bgAlpha = alpha - alpha % 5; // 슬라이더 stepSize(5)에 맞춤
        s.langMode = p.getString("langMode", "both");
        s.audioSource = p.getString("audioSource", "internal");
        s.sttModel = p.getString("sttModel", "small");
        s.sttEngine = p.getString("sttEngine", "online");
        s.translateEngine = p.getString("translateEngine", "llm");
        s.bottomSubtitle = p.getBoolean("bottomSubtitle", true);
        return s;
    }

    void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("fontSize", fontSize)
            .putInt("bgAlpha", bgAlpha)
            .putString("langMode", langMode)
            .putString("audioSource", audioSource)
            .putString("sttModel", sttModel)
            .putString("sttEngine", sttEngine)
            .putString("translateEngine", translateEngine)
            .putBoolean("bottomSubtitle", bottomSubtitle)
            .apply();
    }

    void putExtras(Intent intent) {
        intent.putExtra("fontSize", fontSize);
        intent.putExtra("bgAlpha", bgAlpha);
        intent.putExtra("langMode", langMode);
        intent.putExtra("audioSource", audioSource);
        intent.putExtra("sttModel", sttModel);
        intent.putExtra("sttEngine", sttEngine);
        intent.putExtra("translateEngine", translateEngine);
        intent.putExtra("bottomSubtitle", bottomSubtitle);
    }

    /** 실행 중인 자막 서비스에 즉시 반영 */
    void broadcastIfRunning(Context ctx) {
        broadcastIfRunning(ctx, false);
    }

    /** keyChanged=true 면 서비스가 새 API 키로 온라인 인식을 다시 연결 */
    void broadcastIfRunning(Context ctx, boolean keyChanged) {
        broadcastIfRunning(ctx, keyChanged ? "sttKeyChanged" : null);
    }

    /** flagExtra: "sttKeyChanged"(Deepgram 키) / "llmKeyChanged"(Claude 키) / null */
    void broadcastIfRunning(Context ctx, String flagExtra) {
        if (!SubtitleOverlayService.isRunning) return;
        Intent intent = new Intent(SubtitleOverlayService.ACTION_UPDATE_SETTINGS);
        intent.setPackage(ctx.getPackageName()); // 앱 내부로만 전달
        putExtras(intent);
        if (flagExtra != null) intent.putExtra(flagExtra, true);
        ctx.sendBroadcast(intent);
    }

    static String anthropicKey(Context ctx) {
        return ctx.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).getString("anthropic_key", "").trim();
    }

    static void saveAnthropicKey(Context ctx, String key) {
        ctx.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).edit()
            .putString("anthropic_key", key == null ? "" : key.trim()).apply();
    }

    // ── Deepgram API 키 (백업에서 제외되는 별도 저장소) ──

    private static final String SECRETS = "secrets";

    static String deepgramKey(Context ctx) {
        return ctx.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).getString("deepgram_key", "").trim();
    }

    static void saveDeepgramKey(Context ctx, String key) {
        ctx.getSharedPreferences(SECRETS, Context.MODE_PRIVATE).edit()
            .putString("deepgram_key", key == null ? "" : key.trim()).apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
