package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.ProgressBar;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.download.DownloadSeries;

import java.util.ArrayList;

public class DownloadSeriesAdapter extends BaseQuickAdapter<DownloadSeries, BaseViewHolder> {

    public DownloadSeriesAdapter() {
        super(R.layout.item_download, new ArrayList<DownloadSeries>());
    }

    @Override
    protected void convert(BaseViewHolder helper, DownloadSeries item) {
        helper.setText(R.id.tvName, item.name == null ? "" : item.name);
        StringBuilder status = new StringBuilder("已完成 " + item.done + "/" + item.total + " 集");
        if (item.active > 0) status.append("  下载中 ").append(item.active).append(" 集");
        if (item.failed > 0) status.append("  失败 ").append(item.failed).append(" 集");
        helper.setText(R.id.tvStatus, status.toString());
        ProgressBar progressBar = helper.getView(R.id.progress);
        progressBar.setMax(100);
        progressBar.setProgress(item.progress);
        helper.setText(R.id.tvAction, "查看");
        helper.getView(R.id.tvAction).setVisibility(View.VISIBLE);
        helper.getView(R.id.tvAction).setClickable(false);
    }
}
