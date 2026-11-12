package com.suboverlay.service;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vosk 모델 다운로드/설치 관리.
 *  - 임시 파일/폴더에 받은 뒤 검증이 끝나면 rename → 중간에 끊겨도 깨진 모델이 남지 않음
 *  - 네트워크 실패 시 지수 백오프 재시도
 *  - Zip Slip(경로 조작) 방어
 */
public final class VoskModelManager {

    private static final String TAG = "SubOverlay";
    private static final int MAX_ATTEMPTS = 4;

    public enum ModelSpec {
        SMALL("small", "vosk-model-small-en-us-0.15", "약 40MB"),
        LARGE("large", "vosk-model-en-us-0.22-lgraph", "약 128MB");

        public final String key;
        public final String dirName;
        public final String sizeLabel;

        ModelSpec(String key, String dirName, String sizeLabel) {
            this.key = key;
            this.dirName = dirName;
            this.sizeLabel = sizeLabel;
        }

        public String url() {
            return "https://alphacephei.com/vosk/models/" + dirName + ".zip";
        }

        public static ModelSpec fromKey(String key) {
            return "large".equals(key) ? LARGE : SMALL;
        }
    }

    public interface StatusListener {
        void onStatus(String msg);
    }

    /** 취소 확인용 (서비스 종료 시 다운로드 중단) */
    public interface CancelSignal {
        boolean isCancelled();
    }

    private final File baseDir;
    private final StatusListener status;
    private final CancelSignal cancel;

    public VoskModelManager(File baseDir, StatusListener status, CancelSignal cancel) {
        this.baseDir = baseDir;
        this.status = status;
        this.cancel = cancel;
    }

    public File modelDir(ModelSpec spec) {
        return new File(baseDir, spec.dirName);
    }

    /** 모델 필수 파일이 있는지 확인 */
    public boolean isInstalled(ModelSpec spec) {
        File dir = modelDir(spec);
        return new File(dir, "am/final.mdl").isFile()
            && new File(dir, "conf/model.conf").isFile();
    }

    /** 깨진 모델 삭제 (로드 실패 시 재다운로드 유도) */
    public void delete(ModelSpec spec) {
        deleteRecursive(modelDir(spec));
    }

    /** 설치되어 있지 않으면 다운로드+설치. 성공 시 모델 폴더 반환 */
    public File ensureInstalled(ModelSpec spec) throws IOException {
        File finalDir = modelDir(spec);
        if (isInstalled(spec)) return finalDir;

        // 이전 버전에서 압축 해제 도중 끊긴 불완전한 폴더 정리
        deleteRecursive(finalDir);

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkCancelled();
            try {
                installOnce(spec);
                return finalDir;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "모델 설치 실패 (" + attempt + "/" + MAX_ATTEMPTS + ")", e);
                if (attempt < MAX_ATTEMPTS) {
                    long waitSec = 3L << (attempt - 1); // 3, 6, 12초
                    status.onStatus("⚠️ 다운로드 실패, " + waitSec + "초 후 재시도 ("
                        + attempt + "/" + MAX_ATTEMPTS + ")\n" + e.getMessage());
                    sleepCancellable(waitSec * 1000);
                }
            }
        }
        throw last != null ? last : new IOException("모델 설치 실패");
    }

    private void installOnce(ModelSpec spec) throws IOException {
        File zipPart = new File(baseDir, spec.dirName + ".zip.part");
        File tmpRoot = new File(baseDir, spec.dirName + ".tmp");
        try {
            status.onStatus("📥 STT 모델 다운로드 중... (" + spec.sizeLabel + ")");
            download(spec.url(), zipPart);

            status.onStatus("📦 압축 해제 중...");
            deleteRecursive(tmpRoot);
            if (!tmpRoot.mkdirs()) throw new IOException("임시 폴더 생성 실패");
            unzip(zipPart, tmpRoot);

            // zip 내부는 보통 "<dirName>/..." 구조. 혹시 최상위에 바로 있으면 tmpRoot 자체가 모델
            File extracted = new File(tmpRoot, spec.dirName);
            if (!extracted.isDirectory()) extracted = tmpRoot;
            if (!new File(extracted, "am/final.mdl").isFile()
                || !new File(extracted, "conf/model.conf").isFile()) {
                throw new IOException("모델 파일 구성이 올바르지 않음");
            }

            File finalDir = modelDir(spec);
            deleteRecursive(finalDir);
            if (!extracted.renameTo(finalDir)) throw new IOException("모델 폴더 이동 실패");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipPart.delete();
            deleteRecursive(tmpRoot);
        }
    }

    private void download(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);

            long total = conn.getContentLengthLong();
            if (total > 0 && baseDir.getUsableSpace() < total * 3) {
                throw new IOException("저장 공간 부족 (필요: 약 " + (total * 3 / 1_000_000) + "MB)");
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                long downloaded = 0;
                int read, lastPct = -1;
                while ((read = in.read(buf)) != -1) {
                    checkCancelled();
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int pct = (int) (downloaded * 100 / total);
                        if (pct != lastPct) { // 상태 갱신 폭주 방지
                            lastPct = pct;
                            status.onStatus("📥 STT 모델 다운로드 중... " + pct + "%");
                        }
                    }
                }
                if (total > 0 && downloaded != total) {
                    throw new IOException("다운로드가 중간에 끊김 (" + downloaded + "/" + total + ")");
                }
                out.getFD().sync();
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled();
                File outFile = new File(destDir, entry.getName());
                // Zip Slip 방어: 대상 폴더 밖으로 나가는 경로 차단
                if (!outFile.getCanonicalPath().startsWith(destCanonical)) {
                    throw new IOException("잘못된 압축 항목: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("취소됨");
        }
    }

    private void sleepCancellable(long ms) throws InterruptedIOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancelled();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("취소됨");
            }
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
