package com.github.tvbox.osc.util;

import android.view.View;
import android.view.ViewGroup;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Applies TV-specific UI adjustments to shared activities.
 */
public final class TvUiHelper {

    private TvUiHelper() {
    }

    public static void applyRecyclerViewTv(RecyclerView recyclerView, int spanCount) {
        if (!UiModeHelper.isTvMode() || recyclerView == null) return;
        if (spanCount > 1) {
            recyclerView.setLayoutManager(new V7GridLayoutManager(recyclerView.getContext(), spanCount));
        } else {
            recyclerView.setLayoutManager(new V7LinearLayoutManager(recyclerView.getContext()));
        }
        recyclerView.setFocusable(true);
        recyclerView.setFocusableInTouchMode(true);
    }

    public static void makeFocusable(View view) {
        if (!UiModeHelper.isTvMode() || view == null) return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                makeFocusable(group.getChildAt(i));
            }
        }
    }

    public static int historySpanCount() {
        return UiModeHelper.isTvMode() ? 5 : 3;
    }

    public static int detailSeriesSpanCount() {
        return UiModeHelper.isTvMode() ? 6 : 4;
    }

    public static int topPaddingDp() {
        return UiModeHelper.isTvMode() ? 12 : 44;
    }
}
