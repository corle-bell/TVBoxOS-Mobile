package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityTvMainBinding
import com.github.tvbox.osc.ui.fragment.TvGridFragment
import com.github.tvbox.osc.ui.fragment.TvHomeFragment
import com.github.tvbox.osc.ui.fragment.TvMyFragment
import kotlin.system.exitProcess

class TvMainActivity : BaseVbActivity<ActivityTvMainBinding>() {

    var useCacheConfig = false
    private val navViews = mutableListOf<View>()
    private var exitTime = 0L
    private var currentNavIndex = 0
    private var homeFragment: TvHomeFragment? = null

    override fun init() {
        useCacheConfig = intent.extras?.getBoolean(com.github.tvbox.osc.constant.IntentKey.CACHE_CONFIG_CHANGED, false) ?: false

        navViews.addAll(
            listOf(
                mBinding.navHome,
                mBinding.navLive,
                mBinding.navHistory,
                mBinding.navCollect,
                mBinding.navLocal,
                mBinding.navSetting,
                mBinding.navAbout
            )
        )
        navViews.forEachIndexed { index, view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnClickListener { selectNav(index) }
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    updateNavSelection(index)
                }
            }
        }
        selectNav(0)
    }

    private fun selectNav(index: Int) {
        currentNavIndex = index
        updateNavSelection(index)
        when (index) {
            0 -> showHome()
            1 -> jumpActivity(LiveActivity::class.java)
            2 -> jumpActivity(HistoryActivity::class.java)
            3 -> jumpActivity(CollectActivity::class.java)
            4 -> jumpActivity(MovieFoldersActivity::class.java)
            5 -> jumpActivity(SettingActivity::class.java)
            6 -> showMyFragment()
        }
    }

    private fun updateNavSelection(index: Int) {
        navViews.forEachIndexed { i, view ->
            view.isSelected = i == index
        }
    }

    private fun showHome() {
        val fragment = homeFragment ?: TvHomeFragment().also { homeFragment = it }
        showContentFragment(fragment)
    }

    private fun showMyFragment() {
        showContentFragment(TvMyFragment())
    }

    private fun showContentFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commitAllowingStateLoss()
    }

    override fun onBackPressed() {
        if (currentNavIndex != 0) {
            mBinding.navHome.requestFocus()
            selectNav(0)
            return
        }
        val home = homeFragment
        if (home != null && home.isAdded) {
            val childFragments = home.allFragments
            if (childFragments.isNotEmpty()) {
                val fragment = childFragments[home.tabIndex]
                if (fragment is TvGridFragment) {
                    if (!fragment.restoreView()) {
                        if (!home.scrollToFirstTab()) {
                            confirmExit()
                        }
                        return
                    }
                    return
                }
            }
        }
        confirmExit()
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
