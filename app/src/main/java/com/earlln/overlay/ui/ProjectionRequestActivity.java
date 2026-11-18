package com.earlln.overlay.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import com.earlln.overlay.service.SubtitleOverlayService;

/**
 * 화면 공유가 끝난 뒤 📷 를 눌렀을 때, 앱을 다시 시작하지 않고 그 자리에서
 * '화면 공유 허용' 창만 띄우는 투명 화면. 결과는 실행 중인 서비스로 전달한다.
 */
public class ProjectionRequestActivity extends Activity {

    private static final int REQ_PROJECTION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // 회전 등으로 재생성된 경우 중복 요청 방지
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            notifyService(null, 0);
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode == RESULT_OK && data != null) {
            notifyService(data, resultCode);
        } else {
            Toast.makeText(this, "화면 공유를 허용해야 화면 번역을 할 수 있습니다", Toast.LENGTH_SHORT).show();
            notifyService(null, resultCode);
        }
        finish();
    }

    private void notifyService(Intent data, int resultCode) {
        if (!SubtitleOverlayService.isRunning) return; // 그 사이 자막이 종료된 경우
        Intent i = new Intent(this, SubtitleOverlayService.class);
        if (data != null) {
            i.putExtra(SubtitleOverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode);
            i.putExtra(SubtitleOverlayService.EXTRA_PROJECTION_DATA, data);
        } else {
            i.setAction(SubtitleOverlayService.ACTION_PROJECTION_DENIED);
        }
        try {
            startService(i); // 이 화면이 보이는 동안(포그라운드)이므로 허용됨
        } catch (Exception ignored) {}
    }
}
