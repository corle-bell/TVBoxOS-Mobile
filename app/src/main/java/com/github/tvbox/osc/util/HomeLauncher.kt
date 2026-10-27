package com.github.tvbox.osc.util

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.TvMainActivity

object HomeLauncher {

    /**
     * 清空任务栈并按当前界面模式重新打开首页
     */
    @JvmStatic
    fun restart(context: Context, mode: ApiConfig.LoadMode) {
        val target = if (UiModeHelper.isTvMode()) TvMainActivity::class.java else MainActivity::class.java
        val intent = Intent(context, target)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        when (mode) {
            ApiConfig.LoadMode.CACHE -> intent.putExtra(IntentKey.CACHE_CONFIG_CHANGED, true)
            ApiConfig.LoadMode.NETWORK -> intent.putExtra(IntentKey.FORCE_REFRESH, true)
            ApiConfig.LoadMode.CACHE_THEN_REFRESH -> {}
        }
        context.startActivity(intent)
    }

    @JvmStatic
    fun loadMode(intent: Intent?): ApiConfig.LoadMode {
        val extras = intent?.extras ?: return ApiConfig.LoadMode.CACHE_THEN_REFRESH
        return when {
            extras.getBoolean(IntentKey.FORCE_REFRESH, false) -> ApiConfig.LoadMode.NETWORK
            extras.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false) -> ApiConfig.LoadMode.CACHE
            else -> ApiConfig.LoadMode.CACHE_THEN_REFRESH
        }
    }
}
