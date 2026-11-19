package com.earlln.overlay.note;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.earlln.overlay.R;

import java.util.List;

/** 자막 노트 목록 어댑터 (메인 스레드 전용) */
final class NoteAdapter extends RecyclerView.Adapter<NoteAdapter.VH> {

    interface OnLongPress {
        void onLongPress(NoteEntry entry);
    }

    private static final int COLOR_KO = 0xFFFFFFFF;
    private static final int COLOR_KO_DIM = 0xFF8888AA;

    private final List<NoteEntry> entries;
    private final OnLongPress onLongPress;
    private String langMode = "both";
    private float enSize = 14f;
    private float koSize = 16f;

    NoteAdapter(List<NoteEntry> entries, OnLongPress onLongPress) {
        this.entries = entries;
        this.onLongPress = onLongPress;
        setHasStableIds(true);
    }

    void setDisplay(String langMode, float enSize, float koSize) {
        this.langMode = langMode;
        this.enSize = enSize;
        this.koSize = koSize;
    }

    @Override public long getItemId(int position) { return entries.get(position).id; }
    @Override public int getItemCount() { return entries.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_note_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        NoteEntry e = entries.get(position);
        h.en.setTextSize(enSize);
        h.ko.setTextSize(koSize);

        boolean hasKo = e.ko != null && !e.ko.isEmpty();

        // 영어 줄: "ko" 모드에서는 번역이 아직 없을 때만 자리 표시용으로 보여줌
        boolean showEn = !"ko".equals(langMode) || !hasKo;
        h.en.setVisibility(showEn ? View.VISIBLE : View.GONE);
        h.en.setText(e.fromScreen ? "📷 " + e.en : e.en);

        // 한국어 줄
        if ("en".equals(langMode)) {
            h.ko.setVisibility(View.GONE);
        } else if (hasKo) {
            h.ko.setVisibility(View.VISIBLE);
            h.ko.setTextColor(COLOR_KO);
            String ko = e.llm ? "✨ " + e.ko : e.ko; // AI 번역 표시
            h.ko.setText(e.fromScreen && !showEn ? "📷 " + ko : ko);
        } else if (e.koState == NoteEntry.KoState.FAILED) {
            h.ko.setVisibility(View.VISIBLE);
            h.ko.setTextColor(COLOR_KO_DIM);
            h.ko.setText("(번역 실패)");
        } else if (e.koState == NoteEntry.KoState.PENDING) {
            h.ko.setVisibility(View.VISIBLE);
            h.ko.setTextColor(COLOR_KO_DIM);
            h.ko.setText("번역 중…");
        } else {
            h.ko.setVisibility(View.GONE);
        }

        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) onLongPress.onLongPress(entries.get(pos));
            return true;
        });
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView en, ko;
        VH(View v) {
            super(v);
            en = v.findViewById(R.id.tvEntryEn);
            ko = v.findViewById(R.id.tvEntryKo);
        }
    }
}
