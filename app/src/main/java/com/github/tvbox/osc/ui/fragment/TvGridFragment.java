package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.Stack;

/**
 * TV grid browsing with D-pad focus support.
 */
public class TvGridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private RecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private View focusedView = null;

    private static class GridInfo {
        String sortID = "";
        RecyclerView mGridView;
        GridAdapter gridAdapter;
        int page = 1;
        int maxPage = 1;
        boolean isLoad = false;
        View focusedView = null;
    }

    private final Stack<GridInfo> mGrids = new Stack<>();

    public static TvGridFragment newInstance(MovieSort.SortData sortData) {
        return new TvGridFragment().setArguments(sortData);
    }

    public TvGridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_tv_grid;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));
    }

    private void changeView(String id, Boolean isFolder) {
        if (isFolder) {
            this.sortData.flag = "1";
        } else {
            this.sortData.flag = null;
        }
        initView();
        this.sortData.id = id;
        initViewModel();
        initData();
    }

    public char getUITag() {
        return (sortData == null || sortData.flag == null || sortData.flag.length() == 0)
                ? '0' : sortData.flag.charAt(0);
    }

    public boolean enableFastSearch() {
        return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1');
    }

    private void saveCurrentView() {
        if (this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }

    public boolean restoreView() {
        if (mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView);
        GridInfo info = mGrids.pop();
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
        if (mGridView != null) mGridView.requestFocus();
        return true;
    }

    private void createView() {
        this.saveCurrentView();
        if (mGridView == null) {
            mGridView = findViewById(R.id.mGridView);
        } else {
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(8, 8);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(),
                    mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        gridAdapter = new GridAdapter(true);
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
    }

    private int getSpanCount() {
        int width = ScreenUtils.getScreenWidth();
        int itemWidth = (int) (190 * getResources().getDisplayMetrics().density);
        int span = width / itemWidth;
        return Math.max(4, Math.min(span, 6));
    }

    private void initView() {
        this.createView();
        mGridView.setAdapter(gridAdapter);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, getSpanCount()));

        gridAdapter.setOnLoadMoreListener(() -> {
            gridAdapter.setEnableLoadMore(true);
            sourceViewModel.getList(sortData, page);
        }, mGridView);

        gridAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            Movie.Video video = gridAdapter.getData().get(position);
            if (video != null) {
                Bundle bundle = new Bundle();
                bundle.putString("id", video.id);
                bundle.putString("sourceKey", video.sourceKey);
                bundle.putString("title", video.name);
                SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
                if (("12".indexOf(getUITag()) != -1)
                        && (video.tag.equals("folder") || video.tag.equals("cover"))) {
                    focusedView = view;
                    changeView(video.id, video.tag.equals("folder"));
                } else if (homeSourceBean.isQuickSearch()
                        && Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)
                        && enableFastSearch()) {
                    jumpActivity(FastSearchActivity.class, bundle);
                } else {
                    if (TextUtils.isEmpty(video.id) || video.id.startsWith("msearch:")) {
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(DetailActivity.class, bundle);
                    }
                }
            }
        });

        gridAdapter.setLoadMoreView(new LoadMoreView());
        findViewById(R.id.btn_filter).setOnClickListener(view -> showFilter());
        setLoadSir2(mGridView);
    }

    private void initViewModel() {
        if (sourceViewModel != null) return;
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null
                        && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (page > maxPage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                        if (page > 2) {
                            Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        showEmpty();
                    } else {
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                        gridAdapter.loadMoreEnd();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty();
    }

    private void initData() {
        if (ApiConfig.get().getHomeSourceBean().getApi() == null) {
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        mGridView.scrollToPosition(0);
        sourceViewModel.getList(sortData, page);
    }

    public void showFilter() {
        if (sortData != null && !sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            gridFilterDialog.setData(sortData);
            gridFilterDialog.setOnDismiss(() -> {
                page = 1;
                initData();
            });
        }
        if (gridFilterDialog != null) {
            gridFilterDialog.show();
        }
    }
}
