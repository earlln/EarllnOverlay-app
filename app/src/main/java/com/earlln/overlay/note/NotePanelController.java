package com.earlln.overlay.note;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.earlln.overlay.R;

import java.util.ArrayList;
import java.util.List;

/**
 * '📝 자막 노트' 오버레이 UI 전체를 관리한다. (메인 스레드 전용)
 *
 *  - 플로팅 버튼 : 화면 위에 항상 떠 있음. 탭 → 패널 열기/닫기, 드래그 → 이동. 아래에 상태 안내 라벨
 *  - 패널        : 우측 반투명 창. 확정 문장 + 번역이 차곡차곡 쌓임. 이동/확장/숨기기/닫기/복사/공유
 *  - 탭          : 패널을 숨겼을 때 화면 오른쪽 가장자리에 남는 작은 탭 (새 문장 수 표시)
 */
public final class NotePanelController {

    private static final String TAG = "EarllnOverlay";
    private static final int MAX_ENTRIES = 500;
    private static final long STATUS_HIDE_MS = 6000;

    private enum PanelState { CLOSED, OPEN, HIDDEN }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler;
    private final int touchSlop;

    private final List<NoteEntry> entries = new ArrayList<>();
    private final NoteAdapter adapter;

    // 플로팅 버튼
    private View buttonRoot;
    private TextView tvStatus;
    private WindowManager.LayoutParams buttonLp;

    // 패널
    private View panelRoot;
    private TextView tvTitle, tvEmpty, tvLiveEn, tvLiveKo, btnExpand;
    private View liveContainer;
    private RecyclerView rv;
    private WindowManager.LayoutParams panelLp;
    private boolean panelPositioned = false;

    // 숨김 탭
    private TextView tabView;
    private WindowManager.LayoutParams tabLp;

    private PanelState state = PanelState.CLOSED;
    private boolean expanded = false;
    private int unseen = 0;

    // 진행 중 문장
    private String liveEn, liveKo;

    // 설정
    private String langMode = "both";
    private int fontSize = 18;
    private int bgAlpha = 180;

    private final Runnable hideStatusRunnable = () -> {
        if (tvStatus != null) tvStatus.setVisibility(View.GONE);
    };

    public NotePanelController(Context ctx, Handler mainHandler) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.handler = mainHandler;
        this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        this.adapter = new NoteAdapter(entries, this::copyEntry);
    }

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /** 플로팅 버튼 표시. 오버레이 권한이 없으면 false */
    public boolean show() {
        if (buttonRoot != null) return true;
        try {
            buttonRoot = LayoutInflater.from(ctx).inflate(R.layout.overlay_note_button, null);
            tvStatus = buttonRoot.findViewById(R.id.tvNoteStatus);
            View btn = buttonRoot.findViewById(R.id.btnNote);

            buttonLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
            buttonRoot.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            buttonLp.x = Math.max(0, (screenW() - buttonRoot.getMeasuredWidth()) / 2);
            buttonLp.y = dp(36);

            btn.setOnTouchListener(new DragListener(buttonRoot, buttonLp, this::togglePanel, false));
            wm.addView(buttonRoot, buttonLp);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "플로팅 버튼 생성 실패", e);
            buttonRoot = null;
            return false;
        }
    }

    /** 상태 안내 (버튼 아래 작은 라벨). 오류(❌)가 아니면 잠시 후 자동으로 숨김 */
    public void setStatus(String msg) {
        if (tvStatus == null) return;
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideStatusRunnable);
        if (!msg.startsWith("❌")) handler.postDelayed(hideStatusRunnable, STATUS_HIDE_MS);
    }

    public void applySettings(int fontSize, int bgAlpha, String langMode) {
        this.fontSize = fontSize;
        this.bgAlpha = Math.max(0, Math.min(255, bgAlpha));
        this.langMode = langMode == null ? "both" : langMode;
        adapter.setDisplay(this.langMode, enTextSize(), koTextSize());
        adapter.notifyDataSetChanged();
        stylePanel();
        refreshLive();
    }

    public List<NoteEntry> entries() {
        return entries;
    }

    /** 확정 문장 추가 */
    public NoteEntry addEntry(String en) {
        boolean atBottom = isAtBottom();
        NoteEntry e = new NoteEntry(en);
        entries.add(e);
        adapter.notifyItemInserted(entries.size() - 1);
        if (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
            adapter.notifyItemRemoved(0);
        }
        updateCounts();
        if (state == PanelState.OPEN) {
            if (atBottom) scrollToBottom();
        } else {
            unseen++;
            updateTab();
        }
        return e;
    }

    /** 문장의 번역 상태가 바뀌었을 때 */
    public void onEntryUpdated(NoteEntry e) {
        int idx = indexOf(e);
        if (idx < 0) return;
        boolean atBottom = isAtBottom();
        adapter.notifyItemChanged(idx);
        if (state == PanelState.OPEN && atBottom && idx == entries.size() - 1) scrollToBottom();
    }

    /** 진행 중 문장(영어) 갱신 */
    public void setLive(String en) {
        liveEn = en;
        refreshLive();
    }

    /** 진행 중 문장의 번역 갱신 */
    public void setLiveKo(String ko) {
        if (liveEn == null || liveEn.isEmpty()) return;
        liveKo = ko;
        refreshLive();
    }

    public void clearLive() {
        liveEn = null;
        liveKo = null;
        refreshLive();
    }

    /** 화면 회전 등 크기 변경 시 창 위치/크기 재조정 */
    public void onConfigurationChanged() {
        if (state == PanelState.OPEN && panelRoot != null) {
            applyPanelSize();
            clamp(panelRoot, panelLp);
            safeUpdate(panelRoot, panelLp);
        }
        if (state == PanelState.HIDDEN && tabView != null) {
            placeTab();
            safeUpdate(tabView, tabLp);
        }
        if (buttonRoot != null) {
            clamp(buttonRoot, buttonLp);
            safeUpdate(buttonRoot, buttonLp);
        }
    }

    public void destroy() {
        handler.removeCallbacks(hideStatusRunnable);
        safeRemove(panelRoot);
        safeRemove(tabView);
        safeRemove(buttonRoot);
        panelRoot = null;
        tabView = null;
        buttonRoot = null;
        tvStatus = null;
        state = PanelState.CLOSED;
        entries.clear();
    }

    // ── 패널 열기/닫기/숨기기/확장 ────────────────────────────────────────────

    private void togglePanel() {
        if (state == PanelState.OPEN) closePanel();
        else openPanel();
    }

    private void openPanel() {
        if (panelRoot == null && !inflatePanel()) return;
        if (state == PanelState.OPEN) return;
        if (state == PanelState.HIDDEN) {
            safeRemove(tabView);
        }

        applyPanelSize();
        if (!panelPositioned) {
            placePanelDefault();
            panelPositioned = true;
        }
        clamp(panelRoot, panelLp);
        try {
            wm.addView(panelRoot, panelLp);
        } catch (Exception e) {
            Log.e(TAG, "패널 표시 실패", e);
            state = PanelState.CLOSED;
            return;
        }
        state = PanelState.OPEN;
        unseen = 0;
        refreshLive();
        updateCounts();
        scrollToBottom();
    }

    /** ✕ : 패널만 닫음 (기록은 유지, 버튼으로 다시 열면 이어서 보임) */
    private void closePanel() {
        if (state == PanelState.OPEN) safeRemove(panelRoot);
        if (state == PanelState.HIDDEN) safeRemove(tabView);
        state = PanelState.CLOSED;
    }

    /** — : 화면 오른쪽 가장자리의 작은 탭으로 접기 */
    private void hidePanel() {
        if (state != PanelState.OPEN) return;
        safeRemove(panelRoot);
        if (tabView == null) {
            tabView = (TextView) LayoutInflater.from(ctx).inflate(R.layout.overlay_note_tab, null);
            tabLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
            tabView.setOnTouchListener(new DragListener(tabView, tabLp, this::openPanel, true));
        }
        unseen = 0;
        updateTab();
        tabLp.y = panelLp.y + dp(8);
        placeTab();
        try {
            wm.addView(tabView, tabLp);
            state = PanelState.HIDDEN;
        } catch (Exception e) {
            Log.e(TAG, "탭 표시 실패", e);
            state = PanelState.CLOSED;
        }
    }

    private void toggleExpand() {
        if (panelRoot == null) return;
        expanded = !expanded;
        applyPanelSize();
        if (expanded) {
            panelLp.x = (screenW() - panelLp.width) / 2;
            panelLp.y = (screenH() - panelLp.height) / 2;
        } else {
            placePanelDefault();
        }
        clamp(panelRoot, panelLp);
        btnExpand.setText(expanded ? "⤡" : "⤢");
        if (state == PanelState.OPEN) safeUpdate(panelRoot, panelLp);
    }

    private void applyPanelSize() {
        int w = screenW(), h = screenH();
        if (expanded) {
            panelLp.width = w - dp(16);
            panelLp.height = (int) (h * 0.85f);
        } else {
            panelLp.width = Math.min(w - dp(16), Math.max((int) (w * 0.45f), dp(300)));
            panelLp.height = (int) (h * 0.6f);
        }
    }

    /** 기본 위치: 화면 우측 */
    private void placePanelDefault() {
        panelLp.x = screenW() - panelLp.width - dp(8);
        panelLp.y = (int) (screenH() * 0.12f);
    }

    private void placeTab() {
        tabView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        tabLp.x = screenW() - tabView.getMeasuredWidth();
        clamp(tabView, tabLp);
    }

    @SuppressLint("ClickableViewAccessibility")
    private boolean inflatePanel() {
        try {
            panelRoot = LayoutInflater.from(ctx).inflate(R.layout.overlay_note_panel, null);
        } catch (Exception e) {
            Log.e(TAG, "패널 생성 실패", e);
            return false;
        }
        tvTitle = panelRoot.findViewById(R.id.tvPanelTitle);
        tvEmpty = panelRoot.findViewById(R.id.tvEmpty);
        tvLiveEn = panelRoot.findViewById(R.id.tvLiveEn);
        tvLiveKo = panelRoot.findViewById(R.id.tvLiveKo);
        liveContainer = panelRoot.findViewById(R.id.liveContainer);
        btnExpand = panelRoot.findViewById(R.id.btnExpand);
        rv = panelRoot.findViewById(R.id.rvNotes);

        LinearLayoutManager lm = new LinearLayoutManager(ctx);
        lm.setStackFromEnd(true); // 문장이 적을 때도 아래(최신)부터 채움
        rv.setLayoutManager(lm);
        rv.setItemAnimator(null); // 빠르게 쌓일 때 깜빡임 방지
        rv.setAdapter(adapter);

        panelLp = overlayParams(dp(300), dp(400));

        tvTitle.setOnTouchListener(new DragListener(panelRoot, panelLp, null, false));
        panelRoot.findViewById(R.id.btnCopyAll).setOnClickListener(v -> copyAll());
        panelRoot.findViewById(R.id.btnShare).setOnClickListener(v -> shareAll());
        btnExpand.setOnClickListener(v -> toggleExpand());
        panelRoot.findViewById(R.id.btnHide).setOnClickListener(v -> hidePanel());
        panelRoot.findViewById(R.id.btnClose).setOnClickListener(v -> closePanel());

        stylePanel();
        updateCounts();
        return true;
    }

    // ── 복사 / 공유 ──────────────────────────────────────────────────────────

    private void copyEntry(NoteEntry e) {
        copyText(e.toText(langMode), "문장이 복사되었습니다");
    }

    private void copyAll() {
        if (entries.isEmpty()) {
            toast("복사할 문장이 없습니다");
            return;
        }
        copyText(NoteEntry.transcript(entries, langMode),
            "전체 " + entries.size() + "문장이 복사되었습니다");
    }

    private void copyText(String text, String doneMsg) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("자막 노트", text));
            // Android 13+ 는 시스템이 복사 확인 UI를 직접 보여줌
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(doneMsg);
        } catch (Exception ex) {
            Log.e(TAG, "복사 실패", ex);
            toast("복사에 실패했습니다");
        }
    }

    private void shareAll() {
        if (entries.isEmpty()) {
            toast("공유할 문장이 없습니다");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "자막 노트")
            .putExtra(Intent.EXTRA_TEXT, NoteEntry.transcript(entries, langMode));
        Intent chooser = Intent.createChooser(send, "자막 노트 공유");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(chooser);
            hidePanel(); // 공유 화면을 가리지 않도록 패널을 접어둠
        } catch (Exception ex) {
            Log.e(TAG, "공유 실패", ex);
            toast("공유 화면을 열 수 없습니다");
        }
    }

    // ── 표시 갱신 ────────────────────────────────────────────────────────────

    private void stylePanel() {
        if (panelRoot == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.argb(bgAlpha, 14, 14, 22));
        bg.setStroke(dp(1), Color.argb(Math.min(255, bgAlpha / 2 + 40), 124, 106, 255));
        panelRoot.setBackground(bg);
        panelRoot.setClipToOutline(true);
        tvLiveEn.setTextSize(enTextSize() - 1);
        tvLiveKo.setTextSize(koTextSize() - 1);
    }

    private void refreshLive() {
        if (liveContainer == null) return;
        boolean hasEn = liveEn != null && !liveEn.isEmpty();
        boolean hasKo = liveKo != null && !liveKo.isEmpty();
        if (!hasEn) {
            liveContainer.setVisibility(View.GONE);
            return;
        }
        liveContainer.setVisibility(View.VISIBLE);
        boolean showEn = !"ko".equals(langMode) || !hasKo;
        tvLiveEn.setVisibility(showEn ? View.VISIBLE : View.GONE);
        tvLiveEn.setText(liveEn);
        boolean showKo = !"en".equals(langMode) && hasKo;
        tvLiveKo.setVisibility(showKo ? View.VISIBLE : View.GONE);
        if (showKo) tvLiveKo.setText(liveKo);
    }

    private void updateCounts() {
        if (tvTitle != null) {
            tvTitle.setText(entries.isEmpty() ? "📝 자막 노트" : "📝 자막 노트 · " + entries.size());
        }
        if (tvEmpty != null) tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateTab() {
        if (tabView == null) return;
        tabView.setText(unseen > 0 ? "📝\n" + Math.min(unseen, 99) : "📝");
    }

    private boolean isAtBottom() {
        return rv == null || !rv.canScrollVertically(1);
    }

    private void scrollToBottom() {
        if (rv != null && !entries.isEmpty()) rv.scrollToPosition(entries.size() - 1);
    }

    private int indexOf(NoteEntry e) {
        // 최신 문장일수록 업데이트가 잦으므로 뒤에서부터 탐색
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).id == e.id) return i;
        }
        return -1;
    }

    private float enTextSize() { return Math.max(11, fontSize - 4); }
    private float koTextSize() { return Math.max(12, fontSize - 2); }

    // ── 창 관리 유틸 ─────────────────────────────────────────────────────────

    private static WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void clamp(View v, WindowManager.LayoutParams lp) {
        int vw = lp.width > 0 ? lp.width : v.getWidth();
        int vh = lp.height > 0 ? lp.height : v.getHeight();
        lp.x = Math.max(0, Math.min(lp.x, Math.max(0, screenW() - vw)));
        lp.y = Math.max(0, Math.min(lp.y, Math.max(0, screenH() - vh)));
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        if (v == null || !v.isAttachedToWindow()) return;
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
    }

    private void safeRemove(View v) {
        if (v == null || !v.isAttachedToWindow()) return;
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    private DisplayMetrics metrics() {
        return ctx.getResources().getDisplayMetrics();
    }

    private int screenW() { return metrics().widthPixels; }
    private int screenH() { return metrics().heightPixels; }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, metrics());
    }

    private void toast(String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    /** 드래그로 창 이동, 움직이지 않고 떼면 클릭으로 처리 */
    private final class DragListener implements View.OnTouchListener {
        private final View root;
        private final WindowManager.LayoutParams lp;
        private final Runnable onClick;
        private final boolean lockX;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        DragListener(View root, WindowManager.LayoutParams lp, Runnable onClick, boolean lockX) {
            this.root = root;
            this.lp = lp;
            this.onClick = onClick;
            this.lockX = lockX;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                    if (dragging) {
                        if (!lockX) lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        clamp(root, lp);
                        safeUpdate(root, lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging && onClick != null) onClick.run();
                    return true;
                default:
                    return true;
            }
        }
    }
}
