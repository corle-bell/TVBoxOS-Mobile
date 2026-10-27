package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.databinding.ActivityDownloadsBinding;
import com.github.tvbox.osc.download.DownloadTask;
import com.github.tvbox.osc.download.VideoDownloadManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.util.TvUiHelper;
import com.github.tvbox.osc.util.UiModeHelper;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.interfaces.OnConfirmListener;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadEpisodesActivity extends BaseVbActivity<ActivityDownloadsBinding> {
    public static final String EXTRA_KEY = "seriesKey";
    public static final String EXTRA_NAME = "seriesName";

    private DownloadAdapter adapter;
    private String seriesKey;

    @Override
    protected void init() {
        Bundle bundle = getIntent().getExtras();
        seriesKey = bundle == null ? null : bundle.getString(EXTRA_KEY);
        String name = bundle == null ? null : bundle.getString(EXTRA_NAME);
        if (seriesKey == null) {
            finish();
            return;
        }
        if (name != null) mBinding.titleBar.setTitle(name);
        mBinding.topTip.setText("点击已完成的分集播放，可连续播放下一集；长按删除");
        mBinding.tvEmpty.setText("该剧没有下载");
        if (UiModeHelper.isTvMode()) {
            TvUiHelper.applyRecyclerViewTv(mBinding.mGridView, 1);
        } else {
            mBinding.mGridView.setLayoutManager(new LinearLayoutManager(this));
        }
        adapter = new DownloadAdapter();
        mBinding.mGridView.setAdapter(adapter);
        adapter.setActionListener(new DownloadAdapter.ActionListener() {
            @Override
            public void onAction(DownloadTask task) {
                toggle(task);
            }
        });
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                DownloadTask task = DownloadEpisodesActivity.this.adapter.getItem(position);
                if (task == null) return;
                if (task.status == DownloadTask.DONE) {
                    play(task);
                } else {
                    toggle(task);
                }
            }
        });
        adapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                final DownloadTask task = DownloadEpisodesActivity.this.adapter.getItem(position);
                if (task == null) return true;
                new XPopup.Builder(DownloadEpisodesActivity.this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asConfirm("提示", "确定删除该下载?", new OnConfirmListener() {
                            @Override
                            public void onConfirm() {
                                VideoDownloadManager.get().delete(task.id);
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
                new XPopup.Builder(DownloadEpisodesActivity.this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asConfirm("提示", "确定删除该剧全部下载?", new OnConfirmListener() {
                            @Override
                            public void onConfirm() {
                                VideoDownloadManager.get().deleteGroup(seriesKey);
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

    private void toggle(DownloadTask task) {
        if (task.status == DownloadTask.RUNNING || task.status == DownloadTask.WAITING) {
            VideoDownloadManager.get().pause(task.id);
        } else if (task.status == DownloadTask.PAUSED || task.status == DownloadTask.FAILED) {
            VideoDownloadManager.get().resume(task.id);
        }
        reload();
    }

    private void play(DownloadTask target) {
        List<VideoInfo> list = new ArrayList<>();
        int position = -1;
        for (DownloadTask task : VideoDownloadManager.get().groupSnapshot(seriesKey)) {
            if (task.status != DownloadTask.DONE) continue;
            if (task.filePath == null || !new File(task.filePath).exists()) continue;
            if (task.id.equals(target.id)) position = list.size();
            VideoInfo info = new VideoInfo();
            info.setPath(task.filePath);
            info.setDisplayName(task.title);
            info.setTitle(task.title);
            list.add(info);
        }
        if (position < 0) {
            ToastUtils.showShort("文件不存在");
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("videoList", GsonUtils.toJson(list));
        bundle.putInt("position", position);
        jumpActivity(LocalPlayActivity.class, bundle);
    }

    private void reload() {
        if (adapter == null) return;
        List<DownloadTask> tasks = VideoDownloadManager.get().groupSnapshot(seriesKey);
        adapter.update(tasks);
        boolean empty = tasks.isEmpty();
        mBinding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mBinding.topTip.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
