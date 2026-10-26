package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.download.DownloadTask;

import java.util.ArrayList;
import java.util.Locale;

public class DownloadAdapter extends BaseQuickAdapter<DownloadTask, BaseViewHolder> {
    public interface ActionListener {
        void onAction(DownloadTask task);
    }

    private ActionListener actionListener;

    public DownloadAdapter() {
        super(R.layout.item_download, new ArrayList<DownloadTask>());
    }

    public void setActionListener(ActionListener actionListener) {
        this.actionListener = actionListener;
    }

    @Override
    protected void convert(final BaseViewHolder helper, final DownloadTask item) {
        String name = item.episodeLabel();
        helper.setText(R.id.tvName, name == null ? "" : name);
        helper.setText(R.id.tvStatus, statusText(item));
        ProgressBar progressBar = helper.getView(R.id.progress);
        progressBar.setMax(100);
        progressBar.setProgress(item.status == DownloadTask.DONE ? 100 : Math.max(item.progress, 0));
        TextView action = helper.getView(R.id.tvAction);
        if (item.status == DownloadTask.DONE) {
            action.setVisibility(View.GONE);
        } else if (item.status == DownloadTask.RUNNING || item.status == DownloadTask.WAITING) {
            action.setVisibility(View.VISIBLE);
            action.setText("暂停");
        } else if (item.status == DownloadTask.FAILED) {
            action.setVisibility(View.VISIBLE);
            action.setText("重试");
        } else {
            action.setVisibility(View.VISIBLE);
            action.setText("继续");
        }
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionListener != null) actionListener.onAction(item);
            }
        });
    }

    private static String statusText(DownloadTask item) {
        switch (item.status) {
            case DownloadTask.WAITING:
                return "等待中";
            case DownloadTask.RUNNING:
                if (item.type == DownloadTask.TYPE_HLS && item.segmentTotal > 0) {
                    return "下载中 " + item.progress + "% (" + item.segmentDone + "/" + item.segmentTotal + ")";
                }
                return "下载中 " + item.progress + "%  " + formatSize(item.downloadedBytes);
            case DownloadTask.PAUSED:
                return "已暂停 " + item.progress + "%";
            case DownloadTask.DONE:
                long size = item.downloadedBytes > 0 ? item.downloadedBytes : item.totalBytes;
                return "已完成  " + formatSize(size);
            case DownloadTask.FAILED:
                if (item.error == null || item.error.isEmpty()) return "失败";
                return "失败: " + item.error;
            default:
                return "";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024);
        return String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }
}
