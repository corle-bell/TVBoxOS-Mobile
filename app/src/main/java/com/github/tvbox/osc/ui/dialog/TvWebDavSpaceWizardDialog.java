package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import com.lxj.xpopup.XPopup;

/** TV entry for WebDAV space wizard (uses existing wizard with QR + D-pad focus). */
public final class TvWebDavSpaceWizardDialog {

    private final Context context;
    private final int mode;
    private Runnable onCompleted;

    public TvWebDavSpaceWizardDialog(Context context, int mode) {
        this.context = context;
        this.mode = mode;
    }

    public TvWebDavSpaceWizardDialog setOnCompleted(Runnable onCompleted) {
        this.onCompleted = onCompleted;
        return this;
    }

    public void show() {
        new XPopup.Builder(context)
                .autoFocusEditText(false)
                .asCustom(new WebDavSpaceWizardDialog(context, mode).setOnCompleted(onCompleted))
                .show();
    }
}
