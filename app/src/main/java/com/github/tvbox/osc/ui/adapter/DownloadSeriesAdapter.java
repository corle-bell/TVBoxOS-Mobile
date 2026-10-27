package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.download.DownloadSeries;

import java.util.ArrayList;
import java.util.List;

public class DownloadSeriesAdapter extends BaseQuickAdapter<DownloadSeries, BaseViewHolder> {
    private static final Object PAYLOAD_STATE = new Object();

    public DownloadSeriesAdapter() {
        super(R.layout.item_download, new ArrayList<DownloadSeries>());
    }

    /**
     * 剧集未增删且名称、海报地址不变时只刷新状态区域，保留焦点
     */
    public void update(List<DownloadSeries> groups) {
        List<DownloadSeries> old = getData();
        boolean same = old.size() == groups.size();
        for (int i = 0; same && i < groups.size(); i++) {
            same = TextUtils.equals(old.get(i).key, groups.get(i).key)
                    && TextUtils.equals(old.get(i).pic, groups.get(i).pic)
                    && TextUtils.equals(old.get(i).name, groups.get(i).name);
        }
        if (!same) {
            setNewData(groups);
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            old.set(i, groups.get(i));
        }
        notifyItemRangeChanged(getHeaderLayoutCount(), groups.size(), PAYLOAD_STATE);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position, @NonNull List<Object> payloads) {
        int index = position - getHeaderLayoutCount();
        if (payloads.contains(PAYLOAD_STATE) && index >= 0 && index < getData().size()) {
            bindState(holder, getData().get(index));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    protected void convert(BaseViewHolder helper, DownloadSeries item) {
        helper.setText(R.id.tvName, item.name == null ? "" : item.name);
        ImageView thumb = helper.getView(R.id.ivThumb);
        DownloadThumbs.size(thumb, 60, 84);
        bindState(helper, item);
        helper.setText(R.id.tvAction, "查看");
        helper.getView(R.id.tvAction).setVisibility(View.VISIBLE);
        helper.getView(R.id.tvAction).setClickable(false);
    }

    private void bindState(BaseViewHolder helper, DownloadSeries item) {
        DownloadThumbs.poster(helper.getView(R.id.ivThumb), item.key, item.pic);
        StringBuilder status = new StringBuilder("已完成 " + item.done + "/" + item.total + " 集");
        if (item.active > 0) status.append("  下载中 ").append(item.active).append(" 集");
        if (item.failed > 0) status.append("  失败 ").append(item.failed).append(" 集");
        helper.setText(R.id.tvStatus, status.toString());
        ProgressBar progressBar = helper.getView(R.id.progress);
        progressBar.setMax(100);
        progressBar.setProgress(item.progress);
    }
}
