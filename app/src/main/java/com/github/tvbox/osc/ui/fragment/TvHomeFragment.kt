package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.activity.TvMainActivity
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager

class TvHomeFragment : BaseLazyFragment() {

    val tabIndex: Int
        get() = currentTabIndex

    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments = mutableListOf<BaseLazyFragment>()
    private val mHandler = Handler()
    private var mSortDataList: List<SortData> = emptyList()
    private var dataInitOk = false
    private var jarInitOk = false
    private var errorTipDialog: TipDialog? = null
    private var onlyConfigChanged = false
    private var currentTabIndex = 0

    private var tvName: TextView? = null
    private var viewPager: androidx.viewpager.widget.ViewPager? = null
    private var tabRecycler: TvRecyclerView? = null

    override fun getLayoutResID(): Int = R.layout.fragment_tv_home

    override fun init() {
        ControlManager.get().startServer()
        tvName = findViewById(R.id.tvName)
        viewPager = findViewById(R.id.mViewPager)
        tabRecycler = findViewById(R.id.tab_layout)

        findViewById<View>(R.id.tvName)?.setOnClickListener {
            if (dataInitOk && jarInitOk) showSiteSwitch()
            else ToastUtils.showShort("数据源未加载，长按刷新或切换订阅")
        }
        findViewById<View>(R.id.tvName)?.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        findViewById<View>(R.id.search)?.setOnClickListener {
            jumpActivity(FastSearchActivity::class.java)
        }
        findViewById<View>(R.id.iv_history)?.setOnClickListener {
            jumpActivity(HistoryActivity::class.java)
        }
        findViewById<View>(R.id.iv_collect)?.setOnClickListener {
            jumpActivity(CollectActivity::class.java)
        }
        setLoadSir(findViewById(R.id.contentLayout))
        initViewModel()
        initData()
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this)[SourceViewModel::class.java]
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            showSuccess()
            mSortDataList = if (absXml?.classes?.sortList != null) {
                DefaultConfig.adjustSort(
                    ApiConfig.get().homeSourceBean.key,
                    absXml.classes.sortList,
                    true
                )
            } else {
                DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
            }
            initViewPager(absXml)
        }
    }

    private fun initData() {
        val tvMain = mActivity as? TvMainActivity
        onlyConfigChanged = tvMain?.useCacheConfig ?: false
        val home = ApiConfig.get().homeSourceBean
        if (home != null && !home.name.isNullOrEmpty()) {
            tvName?.text = home.name
            tvName?.postDelayed({ tvName?.isSelected = true }, 2000)
        }
        showLoading()
        when {
            dataInitOk && jarInitOk -> sourceViewModel?.getSort(ApiConfig.get().homeSourceBean.key)
            dataInitOk && !jarInitOk -> loadJar()
            else -> loadConfig()
        }
    }

    private fun loadConfig() {
        ApiConfig.get().loadConfig(onlyConfigChanged, object : LoadConfigCallback {
            override fun retry() {
                mHandler.post { initData() }
            }
            override fun success() {
                dataInitOk = true
                if (ApiConfig.get().spider.isEmpty()) jarInitOk = true
                mHandler.postDelayed({ initData() }, 50)
            }
            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                } else showTipDialog(msg)
            }
        }, activity)
    }

    private fun loadJar() {
        if (!ApiConfig.get().spider.isNullOrEmpty()) {
            ApiConfig.get().loadJar(onlyConfigChanged, ApiConfig.get().spider, object : LoadConfigCallback {
                override fun success() {
                    jarInitOk = true
                    mHandler.postDelayed({ initData() }, 50)
                }
                override fun retry() {}
                override fun error(msg: String) {
                    jarInitOk = true
                    mHandler.post {
                        ToastUtils.showShort("更新订阅失败")
                        initData()
                    }
                }
            })
        }
    }

    private fun showTipDialog(msg: String) {
        if (errorTipDialog == null) {
            errorTipDialog = TipDialog(requireActivity(), msg, "重试", "取消", object : TipDialog.OnListener {
                override fun left() {
                    mHandler.post { initData(); errorTipDialog?.hide() }
                }
                override fun right() {
                    dataInitOk = true; jarInitOk = true
                    mHandler.post { initData(); errorTipDialog?.hide() }
                }
                override fun cancel() {
                    dataInitOk = true; jarInitOk = true
                    mHandler.post { initData(); errorTipDialog?.hide() }
                }
                override fun onTitleClick() {
                    errorTipDialog?.hide()
                    jumpActivity(SubscriptionActivity::class.java)
                }
            })
        }
        if (errorTipDialog?.isShowing != true) errorTipDialog?.show()
    }

    private fun initViewPager(absXml: AbsSortXml?) {
        if (mSortDataList.isEmpty()) return
        fragments.clear()
        val tabNames = mutableListOf<String>()
        for (data in mSortDataList) {
            tabNames.add(data.name)
            if (data.id == "my0") {
                fragments.add(
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1
                        && absXml?.videoList != null && absXml.videoList.size > 0
                    ) UserFragment.newInstance(absXml.videoList)
                    else UserFragment.newInstance(null)
                )
            } else {
                fragments.add(TvGridFragment.newInstance(data))
            }
        }
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            tabNames.removeAt(0)
            fragments.removeAt(0)
        }
        viewPager?.adapter = object : FragmentStatePagerAdapter(childFragmentManager) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = fragments.size
        }
        viewPager?.addOnPageChangeListener(object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                currentTabIndex = position
                highlightTab(position)
            }
        })
        setupTabs(tabNames)
    }

    private fun setupTabs(tabNames: List<String>) {
        val recycler = tabRecycler ?: return
        recycler.layoutManager = V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false)
        val adapter = object : com.chad.library.adapter.base.BaseQuickAdapter<String, com.chad.library.adapter.base.BaseViewHolder>(
            R.layout.item_tv_tab, tabNames
        ) {
            override fun convert(helper: com.chad.library.adapter.base.BaseViewHolder, item: String?) {
                val tv = helper.getView<TextView>(R.id.tvTab)
                tv.text = item
                tv.isSelected = helper.adapterPosition == currentTabIndex
            }
        }
        adapter.setOnItemClickListener { _, _, position ->
            viewPager?.setCurrentItem(position, false)
            currentTabIndex = position
            adapter.notifyDataSetChanged()
        }
        recycler.adapter = adapter
    }

    private fun highlightTab(position: Int) {
        currentTabIndex = position
        tabRecycler?.adapter?.notifyDataSetChanged()
    }

    fun scrollToFirstTab(): Boolean {
        return if (currentTabIndex != 0) {
            viewPager?.setCurrentItem(0, false)
            true
        } else false
    }

    private fun showSiteSwitch() {
        val sites = ApiConfig.get().sourceBeanList
        if (sites.isEmpty()) {
            ToastUtils.showLong("暂无可用数据源")
            return
        }
        val dialog = SelectDialog<SourceBean>(requireActivity())
        val tvRecyclerView = dialog.findViewById<TvRecyclerView>(R.id.list)
        tvRecyclerView.layoutManager = V7GridLayoutManager(dialog.context, 2)
        dialog.setTip("请选择首页数据源")
        dialog.setAdapter(object : SelectDialogInterface<SourceBean?> {
            override fun click(value: SourceBean?, pos: Int) {
                ApiConfig.get().setSourceBean(value)
                refreshHomeSources()
            }
            override fun getDisplay(source: SourceBean?) = source?.name ?: ""
        }, object : DiffUtil.ItemCallback<SourceBean>() {
            override fun areItemsTheSame(oldItem: SourceBean, newItem: SourceBean) = oldItem === newItem
            override fun areContentsTheSame(oldItem: SourceBean, newItem: SourceBean) =
                oldItem.key.contentEquals(newItem.key)
        }, sites, sites.indexOf(ApiConfig.get().homeSourceBean))
        dialog.show()
    }

    private fun refreshHomeSources() {
        val intent = Intent(App.getInstance(), TvMainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra(IntentKey.CACHE_CONFIG_CHANGED, true)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlManager.get().stopServer()
    }
}
