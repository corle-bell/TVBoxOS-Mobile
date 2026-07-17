package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.os.Handler
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding
import com.github.tvbox.osc.util.UiModeHelper

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {
    override fun init() {
        App.getInstance().isNormalStart = true

        mBinding.root.postDelayed({
            val target = if (UiModeHelper.isTvMode()) TvMainActivity::class.java else MainActivity::class.java
            startActivity(Intent(this@SplashActivity, target))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        },500)

    }
}