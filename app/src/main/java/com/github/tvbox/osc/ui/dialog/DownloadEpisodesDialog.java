package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.download.DownloadTask;
import com.github.tvbox.osc.ui.adapter.DownloadSelectAdapter;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.BottomPopupView;

import java.util.ArrayList;
import java.util.List;

public class DownloadEpisodesDialog extends BottomPopupView {

    public interface OnConfirmListener {
        void onConfirm(List<DownloadSelectAdapter.Item> items);
    }

    private final String title;
    private final List<DownloadSelectAdapter.Item> items;
    private final int focusPosition;
    private final OnConfirmListener listener;
    private DownloadSelectAdapter adapter;
    private TextView tvConfirm;

    public DownloadEpisodesDialog(@NonNull Context context, String title, List<DownloadSelectAdapter.Item> items,
                                  int focusPosition, OnConfirmListener listener) {
        super(context);
        this.title = title;
        this.items = items;
        this.focusPosition = focusPosition;
        this.listener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_download_episodes;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        ((TextView) findViewById(R.id.tvSummary)).setText(summary());
        tvConfirm = findViewById(R.id.tvConfirm);

        List<VodInfo.VodSeries> series = new ArrayList<>();
        for (DownloadSelectAdapter.Item item : items) series.add(item.series);
        int spanCount = series.isEmpty() ? 4 : Utils.getSeriesSpanCount(series);
        final RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
        rv.addItemDecoration(new GridSpacingItemDecoration(spanCount, 20, true));
        adapter = new DownloadSelectAdapter();
        adapter.setNewData(items);
        rv.setAdapter(adapter);
        adapter.setOnItemClickListener((a, view, position) -> {
            DownloadSelectAdapter.Item item = adapter.getItem(position);
            if (item == null) return;
            if (!item.selectable()) {
                ToastUtils.showShort(item.task != null && item.task.status == DownloadTask.DONE ? "该集已下载" : "该集正在下载");
                return;
            }
            item.checked = !item.checked;
            adapter.notifyItemChanged(position);
            updateConfirm();
        });

        rv.post(() -> {
            int max = ScreenUtils.getScreenHeight() / 2;
            if (rv.getHeight() > max) {
                ViewGroup.LayoutParams lp = rv.getLayoutParams();
                lp.height = max;
                rv.setLayoutParams(lp);
            }
            if (focusPosition >= 0 && focusPosition < items.size()) rv.scrollToPosition(focusPosition);
        });

        findViewById(R.id.tvSelectAll).setOnClickListener(v -> {
            for (DownloadSelectAdapter.Item item : items) {
                if (item.task == null) item.checked = true;
            }
            adapter.notifyDataSetChanged();
            updateConfirm();
        });
        findViewById(R.id.tvClear).setOnClickListener(v -> {
            for (DownloadSelectAdapter.Item item : items) item.checked = false;
            adapter.notifyDataSetChanged();
            updateConfirm();
        });
        tvConfirm.setOnClickListener(v -> {
            List<DownloadSelectAdapter.Item> selected = new ArrayList<>();
            for (DownloadSelectAdapter.Item item : items) {
                if (item.checked) selected.add(item);
            }
            if (selected.isEmpty()) {
                ToastUtils.showShort("请先选择要下载的集数");
                return;
            }
            dismissWith(() -> listener.onConfirm(selected));
        });
        updateConfirm();
    }

    private void updateConfirm() {
        int count = adapter.checkedCount();
        tvConfirm.setText(count > 0 ? "下载所选(" + count + ")" : "下载所选");
    }

    private String summary() {
        int done = 0;
        int active = 0;
        int other = 0;
        for (DownloadSelectAdapter.Item item : items) {
            if (item.task == null) continue;
            if (item.task.status == DownloadTask.DONE) {
                done++;
            } else if (item.task.status == DownloadTask.RUNNING || item.task.status == DownloadTask.WAITING) {
                active++;
            } else {
                other++;
            }
        }
        int none = items.size() - done - active - other;
        StringBuilder sb = new StringBuilder("共 " + items.size() + " 集  已下载 " + done);
        if (active > 0) sb.append("  下载中 ").append(active);
        if (other > 0) sb.append("  暂停/失败 ").append(other);
        sb.append("  未下载 ").append(none);
        return sb.toString();
    }
}
