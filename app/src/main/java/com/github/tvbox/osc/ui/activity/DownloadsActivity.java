package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.databinding.ActivityDownloadsBinding;
import com.github.tvbox.osc.download.DownloadSeries;
import com.github.tvbox.osc.download.VideoDownloadManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.DownloadSeriesAdapter;
import com.github.tvbox.osc.util.TvUiHelper;
import com.github.tvbox.osc.util.UiModeHelper;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.interfaces.OnConfirmListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DownloadsActivity extends BaseVbActivity<ActivityDownloadsBinding> {
    private DownloadSeriesAdapter adapter;
    private final Set<String> ensuredPosters = new HashSet<>();

    @Override
    protected void init() {
        if (UiModeHelper.isTvMode()) {
            TvUiHelper.applyRecyclerViewTv(mBinding.mGridView, 1);
        } else {
            mBinding.mGridView.setLayoutManager(new LinearLayoutManager(this));
        }
        mBinding.topTip.setText("点击剧集查看分集，长按删除整部");
        adapter = new DownloadSeriesAdapter();
        mBinding.mGridView.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                DownloadSeries series = DownloadsActivity.this.adapter.getItem(position);
                if (series == null) return;
                Bundle bundle = new Bundle();
                bundle.putString(DownloadEpisodesActivity.EXTRA_KEY, series.key);
                bundle.putString(DownloadEpisodesActivity.EXTRA_NAME, series.name);
                jumpActivity(DownloadEpisodesActivity.class, bundle);
            }
        });
        adapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                final DownloadSeries series = DownloadsActivity.this.adapter.getItem(position);
                if (series == null) return true;
                new XPopup.Builder(DownloadsActivity.this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asConfirm("提示", "确定删除「" + series.name + "」的全部 " + series.total + " 集?", new OnConfirmListener() {
                            @Override
                            public void onConfirm() {
                                VideoDownloadManager.get().deleteGroup(series.key);
                                reload();
                            }
                        }).show();
                return true;
            }
        });
        mBinding.titleBar.getRightView().setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adapter.getData() == null || adapter.getData().isEmpty()) {
                    ToastUtils.showShort("没有下载任务");
                    return;
                }
                new XPopup.Builder(DownloadsActivity.this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asConfirm("提示", "确定清空下载?", new OnConfirmListener() {
                            @Override
                            public void onConfirm() {
                                VideoDownloadManager.get().clearAll();
                                reload();
                            }
                        }).show();
            }
        });
        reload();
    }

    @Override
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_DOWNLOAD_UPDATE) {
            reload();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        if (adapter == null) return;
        List<DownloadSeries> groups = DownloadSeries.group(VideoDownloadManager.get().snapshot());
        for (DownloadSeries series : groups) {
            if (series.pic != null && ensuredPosters.add(series.key + "\n" + series.pic)) {
                VideoDownloadManager.get().ensurePoster(series.key, series.pic);
            }
        }
        adapter.update(groups);
        boolean empty = groups.isEmpty();
        mBinding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.topTip.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
