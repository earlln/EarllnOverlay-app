package com.suboverlay.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.suboverlay.R;
import com.suboverlay.databinding.ActivityMainBinding;
import com.suboverlay.service.SubtitleOverlayService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private MediaProjectionManager projectionManager;
    private boolean overlayGranted = false;
    private SharedPreferences prefs;

    // MediaProjection 권한 요청
    private final ActivityResultLauncher<Intent> projectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startSubtitleService(result.getResultCode(), result.getData());
            } else {
                Toast.makeText(this, "화면 캡처 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    // 오버레이 권한 확인용
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Settings.canDrawOverlays(this)) {
                overlayGranted = true;
                requestAudioPermission();
            } else {
                Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    // 마이크 권한
    private final ActivityResultLauncher<String> audioPermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                requestMediaProjection();
            } else {
                Toast.makeText(this, "오디오 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        restoreSettings();
        setupUI();
        // 이미 실행 중이면 권한/화면공유를 다시 묻지 않음
        if (!SubtitleOverlayService.isRunning) checkAndRequestPermissions();
    }

    private void setupUI() {
        // 자막 크기 슬라이더
        binding.sliderFontSize.addOnChangeListener((slider, value, fromUser) -> {
            binding.tvFontSizeLabel.setText("글자 크기: " + (int) value + "sp");
            sendSettingsBroadcast();
        });

        // 언어 토글
        binding.toggleLang.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) sendSettingsBroadcast();
        });

        // 서비스 시작/중지 버튼
        binding.btnToggleService.setOnClickListener(v -> {
            if (SubtitleOverlayService.isRunning) {
                stopSubtitleService();
            } else {
                checkAndRequestPermissions();
            }
        });

        // 배경 투명도 슬라이더
        binding.sliderBgAlpha.addOnChangeListener((slider, value, fromUser) -> {
            sendSettingsBroadcast();
        });

        // 오디오 소스 토글
        binding.toggleAudioSource.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                boolean isInternal = checkedId == R.id.btnAudioInternal;
                binding.tvAudioSourceDesc.setText(isInternal
                    ? "폰 내부 소리만 인식 (볼륨 0도 가능)"
                    : "마이크로 주변 소리 인식");
                sendSettingsBroadcast();
            }
        });

        // STT 모델 토글
        binding.toggleSttModel.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateSttModelDesc();
                sendSettingsBroadcast();
            }
        });
        updateSttModelDesc();

        updateButtonState();
    }

    private void updateSttModelDesc() {
        binding.tvSttModelDesc.setText("large".equals(getSelectedSttModel())
            ? R.string.stt_model_large_desc : R.string.stt_model_small_desc);
    }

    /** 이전 실행 때의 설정 복원 (리스너 등록 전에 호출) */
    private void restoreSettings() {
        binding.sliderFontSize.setValue(clamp(prefs.getInt("fontSize", 18), 12, 28));
        binding.tvFontSizeLabel.setText("글자 크기: " + (int) binding.sliderFontSize.getValue() + "sp");
        int alpha = prefs.getInt("bgAlpha", 180);
        binding.sliderBgAlpha.setValue(clamp(alpha - alpha % 5, 0, 255));

        String lang = prefs.getString("langMode", "both");
        binding.toggleLang.check("ko".equals(lang) ? R.id.btnLangKo
            : "en".equals(lang) ? R.id.btnLangEn : R.id.btnLangBoth);

        boolean mic = "mic".equals(prefs.getString("audioSource", "internal"));
        binding.toggleAudioSource.check(mic ? R.id.btnAudioMic : R.id.btnAudioInternal);
        binding.tvAudioSourceDesc.setText(mic ? "마이크로 주변 소리 인식" : "폰 내부 소리만 인식 (볼륨 0도 가능)");

        boolean large = "large".equals(prefs.getString("sttModel", "small"));
        binding.toggleSttModel.check(large ? R.id.btnModelLarge : R.id.btnModelSmall);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void saveSettings() {
        prefs.edit()
            .putInt("fontSize", (int) binding.sliderFontSize.getValue())
            .putInt("bgAlpha", (int) binding.sliderBgAlpha.getValue())
            .putString("langMode", getSelectedLang())
            .putString("audioSource", getSelectedAudioSource())
            .putString("sttModel", getSelectedSttModel())
            .apply();
    }

    private void checkAndRequestPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해주세요", Toast.LENGTH_LONG).show();
            overlayLauncher.launch(intent);
        } else {
            overlayGranted = true;
            requestAudioPermission();
        }
    }

    private void requestAudioPermission() {
        audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
    }

    private void requestMediaProjection() {
        Intent intent = projectionManager.createScreenCaptureIntent();
        projectionLauncher.launch(intent);
    }

    private void startSubtitleService(int resultCode, Intent data) {
        // MediaProjection 은 서비스가 mediaProjection 타입으로 startForeground 한 뒤에
        // 생성해야 한다 (Android 10+ 요구사항). 동의 결과를 서비스로 그대로 전달.
        Intent serviceIntent = new Intent(this, SubtitleOverlayService.class);
        serviceIntent.putExtra(SubtitleOverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode);
        serviceIntent.putExtra(SubtitleOverlayService.EXTRA_PROJECTION_DATA, data);
        putSettings(serviceIntent);
        saveSettings();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "서비스 시작 실패", e);
            Toast.makeText(this, "자막 서비스를 시작하지 못했습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        updateButtonState();
        Toast.makeText(this, "자막 오버레이 시작됨 ✓", Toast.LENGTH_SHORT).show();
    }

    private void stopSubtitleService() {
        Intent serviceIntent = new Intent(this, SubtitleOverlayService.class);
        stopService(serviceIntent);
        // onDestroy가 비동기로 실행되므로 잠시 후 상태 갱신
        binding.btnToggleService.postDelayed(this::updateButtonState, 500);
    }

    private void sendSettingsBroadcast() {
        saveSettings();
        if (!SubtitleOverlayService.isRunning) return;
        Intent intent = new Intent(SubtitleOverlayService.ACTION_UPDATE_SETTINGS);
        intent.setPackage(getPackageName()); // 앱 내부로만 전달 (Android 14 암시적 브로드캐스트 제한 대응)
        putSettings(intent);
        sendBroadcast(intent);
    }

    private void putSettings(Intent intent) {
        intent.putExtra("fontSize", (int) binding.sliderFontSize.getValue());
        intent.putExtra("bgAlpha", (int) binding.sliderBgAlpha.getValue());
        intent.putExtra("langMode", getSelectedLang());
        intent.putExtra("audioSource", getSelectedAudioSource());
        intent.putExtra("sttModel", getSelectedSttModel());
    }

    private String getSelectedSttModel() {
        return binding.toggleSttModel.getCheckedButtonId() == R.id.btnModelLarge
            ? "large" : "small";
    }

    private String getSelectedLang() {
        int id = binding.toggleLang.getCheckedButtonId();
        if (id == R.id.btnLangKo) return "ko";
        if (id == R.id.btnLangEn) return "en";
        return "both";
    }

    private String getSelectedAudioSource() {
        return binding.toggleAudioSource.getCheckedButtonId() == R.id.btnAudioMic
            ? "mic" : "internal";
    }

    private void updateButtonState() {
        if (SubtitleOverlayService.isRunning) {
            binding.btnToggleService.setText("⏹ 자막 중지");
            binding.btnToggleService.setBackgroundColor(0xFFE53935);
            binding.statusIndicator.setText("● 실행 중");
            binding.statusIndicator.setTextColor(0xFF4CAF50);
        } else {
            binding.btnToggleService.setText("▶ 자막 시작");
            binding.btnToggleService.setBackgroundColor(0xFF5C6BC0);
            binding.statusIndicator.setText("○ 대기");
            binding.statusIndicator.setTextColor(0xFF9E9E9E);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonState();
    }
}
