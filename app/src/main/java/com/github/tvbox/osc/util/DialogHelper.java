package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.dialog.TvInputDialog;
import com.github.tvbox.osc.ui.dialog.TvWebDavSpaceWizardDialog;
import com.github.tvbox.osc.ui.dialog.TvWebDavSyncDialog;
import com.github.tvbox.osc.ui.dialog.WebDavSpaceWizardDialog;
import com.github.tvbox.osc.ui.dialog.WebDavSyncDialog;
import com.lxj.xpopup.XPopup;

/**
 * Routes dialogs to TV-friendly or mobile implementations.
 */
public final class DialogHelper {

    public interface InputCallback {
        void onConfirm(String text);
    }

    private DialogHelper() {
    }

    public static void showInput(Activity activity, String title, String hint, InputCallback callback) {
        if (UiModeHelper.isTvMode()) {
            new TvInputDialog(activity, title, hint, callback).show();
        } else {
            new XPopup.Builder(activity)
                    .asInputConfirm(title, "", "", hint, text -> {
                        if (callback != null) callback.onConfirm(text);
                    }, null, com.github.tvbox.osc.R.layout.dialog_input)
                    .show();
        }
    }

    public static void showAbout(Activity activity) {
        if (UiModeHelper.isTvMode()) {
            new XPopup.Builder(activity)
                    .asCustom(new AboutDialog(activity))
                    .show();
        } else {
            new XPopup.Builder(activity)
                    .asCustom(new AboutDialog(activity))
                    .show();
        }
    }

    public static void showWebDavSync(Context context, Runnable onChanged) {
        if (UiModeHelper.isTvMode()) {
            new TvWebDavSyncDialog(context, onChanged).show();
        } else if (context instanceof Activity) {
            new XPopup.Builder(context)
                    .autoFocusEditText(false)
                    .asCustom(new WebDavSyncDialog((Activity) context))
                    .show();
        }
    }

    public static void showWebDavWizard(Context context, int mode, Runnable onCompleted) {
        if (UiModeHelper.isTvMode()) {
            new TvWebDavSpaceWizardDialog(context, mode)
                    .setOnCompleted(onCompleted)
                    .show();
        } else if (context instanceof Activity) {
            new XPopup.Builder(context)
                    .autoFocusEditText(false)
                    .asCustom(new WebDavSpaceWizardDialog((Activity) context, mode)
                            .setOnCompleted(onCompleted))
                    .show();
        }
    }
}
