package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.download.DownloadTask;

import java.util.ArrayList;

public class DownloadSelectAdapter extends BaseQuickAdapter<DownloadSelectAdapter.Item, BaseViewHolder> {

    public static class Item {
        public VodInfo.VodSeries series;
        public int episodeIndex;
        public DownloadTask task;
        public boolean checked;

        public boolean selectable() {
            return task == null || task.status == DownloadTask.PAUSED || task.status == DownloadTask.FAILED;
        }

        public String stateText() {
            if (task == null) return "未下载";
            switch (task.status) {
                case DownloadTask.DONE:
                    return "已下载";
                case DownloadTask.RUNNING:
                    return "下载中";
                case DownloadTask.WAITING:
                    return "等待中";
                case DownloadTask.PAUSED:
                    return "已暂停";
                case DownloadTask.FAILED:
                    return "下载失败";
                default:
                    return "";
            }
        }
    }

    public DownloadSelectAdapter() {
        super(R.layout.item_download_select, new ArrayList<Item>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Item item) {
        View root = helper.getView(R.id.root);
        TextView name = helper.getView(R.id.tvName);
        TextView state = helper.getView(R.id.tvState);
        String label = item.series.name == null ? "" : item.series.name;
        name.setText(item.checked ? "✓ " + label : label);
        state.setText(item.stateText());
        root.setSelected(item.checked);
        root.setAlpha(item.selectable() ? 1f : 0.45f);
        if (item.checked) {
            name.setTextColor(Color.WHITE);
            state.setTextColor(Color.WHITE);
        } else {
            name.setTextColor(ContextCompat.getColor(mContext, R.color.colorPrimary));
            boolean done = item.task != null && item.task.status == DownloadTask.DONE;
            state.setTextColor(ContextCompat.getColor(mContext, done ? R.color.colorAccent : R.color.gray_darker));
        }
    }

    public int checkedCount() {
        int count = 0;
        for (Item item : getData()) {
            if (item.checked) count++;
        }
        return count;
    }
}
