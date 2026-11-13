package com.earlln.overlay.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.earlln.overlay.databinding.ActivityMainBinding;
import com.earlln.overlay.service.SubtitleOverlayService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private MediaProjectionManager projectionManager;
    private boolean overlayGranted = false;

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
        setupUI();
        // 이미 실행 중이면 권한/화면공유를 다시 묻지 않음
        if (!SubtitleOverlayService.isRunning) checkAndRequestPermissions();
    }

    private void setupUI() {
        // 서비스 시작/중지 버튼
        binding.btnToggleService.setOnClickListener(v -> {
            if (SubtitleOverlayService.isRunning) {
                stopSubtitleService();
            } else {
                checkAndRequestPermissions();
            }
        });

        // ⚙️ 설정 화면
        binding.btnSettings.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        updateButtonState();
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
        AppSettings.load(this).putExtras(serviceIntent);

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
