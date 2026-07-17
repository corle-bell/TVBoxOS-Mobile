package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.DialogHelper;

public class TvInputDialog extends BaseDialog {

    private final String title;
    private final String hint;
    private final DialogHelper.InputCallback callback;

    public TvInputDialog(@NonNull Activity context, String title, String hint,
                         DialogHelper.InputCallback callback) {
        super(context, R.style.CustomDialogStyleDim);
        this.title = title;
        this.hint = hint;
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_tv_input);
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.CENTER;
            lp.width = ConvertUtils.dp2px(480f);
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setAttributes(lp);
            window.setWindowAnimations(R.style.DialogFadeAnimation);
        }
        ((TextView) findViewById(R.id.tvTitle)).setText(title);
        EditText et = findViewById(R.id.etInput);
        et.setHint(hint);
        et.setFocusable(true);
        et.requestFocus();
        findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            String text = et.getText() == null ? "" : et.getText().toString().trim();
            if (!TextUtils.isEmpty(text) && callback != null) {
                callback.onConfirm(text);
            }
            dismiss();
        });
        findViewById(R.id.btnCancel).setOnClickListener(v -> dismiss());
    }
}
