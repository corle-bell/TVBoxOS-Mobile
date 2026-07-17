package com.github.tvbox.osc.ui.dialog;

import android.content.Context;

import com.lxj.xpopup.XPopup;

/** TV entry for WebDAV sync settings dialog. */
public final class TvWebDavSyncDialog {

    private final Context context;
    private final Runnable onChanged;

    public TvWebDavSyncDialog(Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
    }

    public void show() {
        WebDavSyncDialog dialog = new WebDavSyncDialog(context);
        new XPopup.Builder(context)
                .autoFocusEditText(false)
                .asCustom(dialog)
                .show();
    }
}
