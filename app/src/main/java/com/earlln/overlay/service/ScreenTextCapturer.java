package com.earlln.overlay.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 버튼을 누를 때만 화면을 한 장 캡처해서 MLKit(온디바이스)으로 글자를 인식한다.
 * 공개 메서드는 메인 스레드에서 호출한다.
 *
 *  - Android 14+ 는 MediaProjection 하나당 createVirtualDisplay 를 한 번만 허용하므로
 *    가상 디스플레이는 한 번 만들어 재사용하고, 쉬는 동안에는 surface 를 떼어 화면 복사를 멈춘다(배터리 절약).
 *  - 캡처 이미지는 메모리에서만 쓰고 저장/전송하지 않는다.
 */
final class ScreenTextCapturer {

    /** 인식된 글자 덩어리 1개 + 실제 화면 좌표상의 위치 */
    static final class Block {
        final String text;
        final android.graphics.Rect box; // null 가능
        Block(String text, android.graphics.Rect box) { this.text = text; this.box = box; }
    }

    interface Callback {
        void onResult(List<Block> blocks);
        void onError(String message);
    }

    private static final String TAG = "EarllnOverlay";
    private static final long FRAME_TIMEOUT_MS = 2000;
    private static final int MAX_WIDTH = 1440;

    private final Context ctx;
    private final Handler main;
    private final TextRecognizer recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private HandlerThread thread;
    private Handler bg;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width, height, dpi;
    /** 캡처 이미지 좌표 → 실제 화면 좌표 배율 (큰 화면은 축소해서 캡처하므로) */
    private float toScreenScale = 1f;

    private final AtomicReference<Callback> pending = new AtomicReference<>();
    private boolean busy = false;
    private boolean released = false;

    private final Runnable timeoutRunnable = () -> {
        Callback cb = pending.getAndSet(null);
        if (cb == null) return;
        detachSurface();
        busy = false;
        cb.onError("화면을 가져오지 못했습니다. 다시 시도해 주세요");
    };

    ScreenTextCapturer(Context ctx, Handler mainHandler) {
        this.ctx = ctx;
        this.main = mainHandler;
    }

    boolean isBusy() {
        return busy;
    }

    /** 새 화면 공유 권한으로 교체 (null 이면 화면 번역 불가) */
    void setProjection(MediaProjection mp) {
        if (mp == projection) return;
        releaseDisplay(); // 이전 권한으로 만든 가상 디스플레이는 재사용할 수 없음
        projection = mp;
    }

    void capture(Callback cb) {
        if (released || busy) return;
        if (projection == null) {
            cb.onError("화면 공유 권한이 없습니다. 자막을 종료 후 다시 시작해 주세요");
            return;
        }
        busy = true;
        ensureThread();
        pending.set(cb);
        try {
            ensureDisplay();
        } catch (Exception e) {
            Log.e(TAG, "가상 디스플레이 생성 실패", e);
            pending.set(null);
            busy = false;
            cb.onError("화면 캡처를 시작하지 못했습니다");
            return;
        }
        main.postDelayed(timeoutRunnable, FRAME_TIMEOUT_MS);
    }

    void release() {
        released = true;
        main.removeCallbacks(timeoutRunnable);
        pending.set(null);
        releaseDisplay();
        try { recognizer.close(); } catch (Exception ignored) {}
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bg = null;
        }
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    private void ensureThread() {
        if (thread != null) return;
        thread = new HandlerThread("ScreenCapture");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    private void ensureDisplay() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, h = dm.heightPixels;
        if (w > MAX_WIDTH) { // 너무 큰 화면은 축소 (글자 인식에는 충분)
            h = h * MAX_WIDTH / w;
            w = MAX_WIDTH;
        }

        boolean sizeChanged = w != width || h != height;
        if (reader == null || sizeChanged) {
            if (reader != null) reader.close();
            width = w;
            height = h;
            dpi = dm.densityDpi;
            toScreenScale = dm.widthPixels / (float) width;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImageAvailable, bg);
        }

        if (display == null) {
            display = projection.createVirtualDisplay("EarllnOverlayScreenText",
                width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, bg);
        } else {
            if (sizeChanged) display.resize(width, height, dpi); // 화면 회전 등
            display.setSurface(reader.getSurface());
        }
    }

    /** 캡처 스레드: 첫 프레임을 비트맵으로 만들어 글자 인식으로 넘긴다 */
    private void onImageAvailable(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        try {
            Callback cb = pending.getAndSet(null);
            if (cb == null) return; // 요청이 없거나 이미 처리됨
            Bitmap bmp = toBitmap(img);
            main.post(() -> {
                main.removeCallbacks(timeoutRunnable);
                detachSurface();
                recognize(bmp, cb);
            });
        } catch (Exception e) {
            Log.e(TAG, "프레임 변환 실패", e);
        } finally {
            img.close();
        }
    }

    private Bitmap toBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowPadding = plane.getRowStride() - pixelStride * img.getWidth();
        Bitmap padded = Bitmap.createBitmap(img.getWidth() + rowPadding / pixelStride,
            img.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        if (rowPadding == 0) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, img.getWidth(), img.getHeight());
        padded.recycle();
        return cropped;
    }

    private void recognize(Bitmap bmp, Callback cb) {
        if (released) { bmp.recycle(); return; }
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener(text -> {
                List<Block> blocks = new ArrayList<>();
                for (Text.TextBlock b : text.getTextBlocks()) {
                    android.graphics.Rect r = b.getBoundingBox();
                    if (r != null && toScreenScale != 1f) {
                        r = new android.graphics.Rect(
                            Math.round(r.left * toScreenScale), Math.round(r.top * toScreenScale),
                            Math.round(r.right * toScreenScale), Math.round(r.bottom * toScreenScale));
                    }
                    blocks.add(new Block(b.getText(), r));
                }
                finish();
                bmp.recycle();
                if (!released) cb.onResult(blocks);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "글자 인식 실패", e);
                finish();
                bmp.recycle();
                if (!released) cb.onError("글자 인식에 실패했습니다");
            });
    }

    private void finish() {
        busy = false;
    }

    /** 쉬는 동안 화면 복사 중단 */
    private void detachSurface() {
        if (display != null) {
            try { display.setSurface(null); } catch (Exception ignored) {}
        }
    }

    private void releaseDisplay() {
        if (display != null) {
            try { display.release(); } catch (Exception ignored) {}
            display = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        width = height = 0;
    }
}
