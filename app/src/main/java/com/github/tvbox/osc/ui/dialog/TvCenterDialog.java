package com.github.tvbox.osc.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;

import xyz.doikki.videoplayer.util.CutoutUtil;

/** Centered dialog for TV-friendly popups. */
public class TvCenterDialog extends Dialog {

    public TvCenterDialog(@NonNull Context context) {
        super(context, R.style.CustomDialogStyleDim);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CutoutUtil.adaptCutoutAboveAndroidP(this, true);
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.CENTER;
            lp.width = ConvertUtils.dp2px(560f);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.6f;
            window.setAttributes(lp);
            window.setWindowAnimations(R.style.DialogFadeAnimation);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }
}
