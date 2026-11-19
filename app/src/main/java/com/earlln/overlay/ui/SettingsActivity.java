package com.earlln.overlay.ui;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.pm.PackageInfoCompat;

import com.earlln.overlay.R;
import com.earlln.overlay.databinding.ActivitySettingsBinding;

/** 설정 화면: 자막/오디오/모델/표시 설정 + 앱 버전. 변경 즉시 저장되고 실행 중인 자막에 반영된다 */
public class SettingsActivity extends AppCompatActivity {

    private static final String DESC_INTERNAL = "폰 내부 소리만 인식 (볼륨 0도 가능)";
    private static final String DESC_MIC = "마이크로 주변 소리 인식";

    private ActivitySettingsBinding binding;
    private AppSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settings = AppSettings.load(this);
        restore();       // 리스너 등록 전에 화면에 반영
        setupListeners();
        showVersion();

        binding.btnBack.setOnClickListener(v -> finish());
    }

    private void restore() {
        binding.sliderFontSize.setValue(settings.fontSize);
        binding.tvFontSizeLabel.setText("글자 크기: " + settings.fontSize + "sp");
        binding.sliderBgAlpha.setValue(settings.bgAlpha);

        binding.toggleLang.check("ko".equals(settings.langMode) ? R.id.btnLangKo
            : "en".equals(settings.langMode) ? R.id.btnLangEn : R.id.btnLangBoth);

        boolean mic = "mic".equals(settings.audioSource);
        binding.toggleAudioSource.check(mic ? R.id.btnAudioMic : R.id.btnAudioInternal);
        binding.tvAudioSourceDesc.setText(mic ? DESC_MIC : DESC_INTERNAL);

        boolean large = "large".equals(settings.sttModel);
        binding.toggleSttModel.check(large ? R.id.btnModelLarge : R.id.btnModelSmall);
        binding.tvSttModelDesc.setText(large ? R.string.stt_model_large_desc : R.string.stt_model_small_desc);

        binding.switchBottomSubtitle.setChecked(settings.bottomSubtitle);

        boolean online = !"offline".equals(settings.sttEngine);
        binding.toggleSttEngine.check(online ? R.id.btnEngineOnline : R.id.btnEngineOffline);
        updateEngineUi(online);
        updateKeyStatus();
    }

    private void updateEngineUi(boolean online) {
        binding.tvSttEngineDesc.setText(online
            ? "Deepgram Nova-3 클라우드 인식 · 가장 정확하고 문장부호 자동 · 인터넷과 API 키 필요"
            : "Vosk 기기 내 인식 · 무료/인터넷 불필요 · 정확도는 온라인보다 낮음");
        binding.groupDeepgramKey.setVisibility(online ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void updateKeyStatus() {
        String key = AppSettings.deepgramKey(this);
        binding.tvKeyStatus.setText(key.isEmpty()
            ? "⚠️ 저장된 키가 없습니다 (키가 없으면 오프라인으로 인식)"
            : "✅ 키 저장됨 (끝자리 …" + key.substring(Math.max(0, key.length() - 4)) + ")");
    }

    private void saveKey() {
        String key = binding.etDeepgramKey.getText().toString().trim();
        if (key.isEmpty()) {
            android.widget.Toast.makeText(this, "키를 입력해 주세요", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        AppSettings.saveDeepgramKey(this, key);
        binding.etDeepgramKey.setText("");
        updateKeyStatus();
        settings.broadcastIfRunning(this, true); // 실행 중이면 새 키로 다시 연결
        android.widget.Toast.makeText(this, "API 키를 저장했습니다", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void setupListeners() {
        binding.sliderFontSize.addOnChangeListener((slider, value, fromUser) -> {
            settings.fontSize = (int) value;
            binding.tvFontSizeLabel.setText("글자 크기: " + settings.fontSize + "sp");
            apply();
        });

        binding.sliderBgAlpha.addOnChangeListener((slider, value, fromUser) -> {
            settings.bgAlpha = (int) value;
            apply();
        });

        binding.toggleLang.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            settings.langMode = checkedId == R.id.btnLangKo ? "ko"
                : checkedId == R.id.btnLangEn ? "en" : "both";
            apply();
        });

        binding.toggleAudioSource.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean mic = checkedId == R.id.btnAudioMic;
            settings.audioSource = mic ? "mic" : "internal";
            binding.tvAudioSourceDesc.setText(mic ? DESC_MIC : DESC_INTERNAL);
            apply();
        });

        binding.toggleSttModel.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean large = checkedId == R.id.btnModelLarge;
            settings.sttModel = large ? "large" : "small";
            binding.tvSttModelDesc.setText(large ? R.string.stt_model_large_desc : R.string.stt_model_small_desc);
            apply();
        });

        binding.toggleSttEngine.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean online = checkedId == R.id.btnEngineOnline;
            settings.sttEngine = online ? "online" : "offline";
            updateEngineUi(online);
            apply();
        });

        binding.btnSaveKey.setOnClickListener(v -> saveKey());

        binding.switchBottomSubtitle.setOnCheckedChangeListener((b, checked) -> {
            settings.bottomSubtitle = checked;
            apply();
        });
    }

    private void apply() {
        settings.save(this);
        settings.broadcastIfRunning(this);
    }

    private void showVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long build = PackageInfoCompat.getLongVersionCode(info);
            binding.tvVersion.setText("v" + info.versionName + " (빌드 " + build + ")");
        } catch (Exception e) {
            binding.tvVersion.setText("알 수 없음");
        }
    }
}
