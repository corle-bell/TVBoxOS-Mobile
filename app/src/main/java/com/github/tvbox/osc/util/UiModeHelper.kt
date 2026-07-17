package com.github.tvbox.osc.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.github.tvbox.osc.base.App
import com.orhanobut.hawk.Hawk

object UiModeHelper {

    const val MODE_AUTO = "AUTO"
    const val MODE_MOBILE = "MOBILE"
    const val MODE_TV = "TV"

    @JvmStatic
    fun getOverride(): String {
        return Hawk.get(HawkConfig.UI_MODE_OVERRIDE, MODE_AUTO)
    }

    @JvmStatic
    fun setOverride(mode: String) {
        Hawk.put(HawkConfig.UI_MODE_OVERRIDE, mode)
    }

    @JvmStatic
    fun isTvMode(): Boolean {
        return when (getOverride()) {
            MODE_TV -> true
            MODE_MOBILE -> false
            else -> detectTvMode(App.getInstance())
        }
    }

    @JvmStatic
    fun detectTvMode(context: Context): Boolean {
        val config = context.resources.configuration
        if (config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return true
        }
        val sw = config.smallestScreenWidthDp
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return sw >= 600 && !hasTouch
    }

    @JvmStatic
    fun getOverrideDisplayName(mode: String): String {
        return when (mode) {
            MODE_TV -> "TV"
            MODE_MOBILE -> "手机"
            else -> "自动"
        }
    }

    @JvmStatic
    val overrideLabels: Array<String>
        get() = arrayOf("自动", "手机", "TV")

    @JvmStatic
    fun labelToMode(label: String): String {
        return when (label) {
            "TV" -> MODE_TV
            "手机" -> MODE_MOBILE
            else -> MODE_AUTO
        }
    }

    @JvmStatic
    fun modeToLabel(mode: String): String {
        return getOverrideDisplayName(mode)
    }

    @JvmStatic
    fun overrideIndex(): Int {
        return when (getOverride()) {
            MODE_MOBILE -> 1
            MODE_TV -> 2
            else -> 0
        }
    }
}
