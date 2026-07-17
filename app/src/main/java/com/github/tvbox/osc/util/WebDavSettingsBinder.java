package com.github.tvbox.osc.util;

import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.sync.webdav.WebDavSyncUiHelper;
import com.github.tvbox.osc.ui.dialog.WebDavSpaceWizardDialog;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import java.util.ArrayList;

/**
 * Binds WebDAV settings rows shared by mobile and TV setting layouts.
 */
public final class WebDavSettingsBinder {

    public interface Host {
        View getWebDavSyncRow();

        TextView getWebDavSyncStatusView();

        View getWebDavSyncNowButton();

        AppCompatActivity getActivity();

        void onWebDavStatusChanged();
    }

    private WebDavSettingsBinder() {
    }

    public static void bind(Host host) {
        refreshStatus(host);
        WebDavSyncUiHelper.refreshDeviceCount(false, count ->
                host.getActivity().runOnUiThread(() -> refreshStatus(host)));

        host.getWebDavSyncRow().setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            DialogHelper.showWebDavSync(host.getActivity(), () -> refreshStatus(host));
        });

        host.getWebDavSyncRow().setOnLongClickListener(v -> {
            ArrayList<String> options = new ArrayList<>();
            options.add("创建同步空间");
            options.add("加入已有空间");
            SelectDialog<String> dialog = new SelectDialog<>(host.getActivity());
            dialog.setTip("同步空间向导");
            dialog.setAdapter(new SelectDialogInterface<String>() {
                @Override
                public void click(String value, int pos) {
                    int mode = pos == 0
                            ? WebDavSpaceWizardDialog.MODE_CREATE
                            : WebDavSpaceWizardDialog.MODE_JOIN;
                    DialogHelper.showWebDavWizard(host.getActivity(), mode, () -> refreshStatus(host));
                }

                @Override
                public String getDisplay(String value) {
                    return value == null ? "" : value;
                }
            }, SelectDialogAdapter.stringDiff, options, 0);
            dialog.show();
            return true;
        });

        host.getWebDavSyncNowButton().setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            host.getWebDavSyncNowButton().setEnabled(false);
            host.getWebDavSyncStatusView().setText("同步中…");
            WebDavSyncUiHelper.syncNow(host.getActivity(), new WebDavSyncUiHelper.SyncCallback() {
                @Override
                public void onStart() {
                }

                @Override
                public void onSuccess(com.github.tvbox.osc.sync.webdav.WebDavSyncCoordinator.SyncResult result) {
                    host.getActivity().runOnUiThread(() -> {
                        ToastUtils.showShort("同步完成");
                        host.getWebDavSyncNowButton().setEnabled(true);
                        refreshStatus(host);
                        host.onWebDavStatusChanged();
                    });
                }

                @Override
                public void onError(String message) {
                    host.getActivity().runOnUiThread(() -> {
                        ToastUtils.showShort("同步失败: " + message);
                        host.getWebDavSyncNowButton().setEnabled(true);
                        refreshStatus(host);
                    });
                }
            });
        });
    }

    public static void refreshStatus(Host host) {
        host.getWebDavSyncStatusView().setText(WebDavSyncUiHelper.formatStatusSummary());
    }
}
