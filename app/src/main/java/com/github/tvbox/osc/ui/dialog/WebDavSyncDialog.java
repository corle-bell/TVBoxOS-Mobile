package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogWebdavSyncBinding;
import com.github.tvbox.osc.sync.webdav.WebDavCredentialStore;
import com.github.tvbox.osc.sync.webdav.WebDavDeviceId;
import com.github.tvbox.osc.sync.webdav.WebDavSpaceInvite;
import com.github.tvbox.osc.sync.webdav.WebDavSyncCoordinator;
import com.github.tvbox.osc.sync.webdav.WebDavSyncId;
import com.github.tvbox.osc.sync.webdav.WebDavSyncScheduler;
import com.github.tvbox.osc.sync.webdav.WebDavSyncUiHelper;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.CenterPopupView;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebDavSyncDialog extends CenterPopupView {
    private DialogWebdavSyncBinding mBinding;
    private final WebDavCredentialStore credentialStore = new WebDavCredentialStore();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int intervalIndex;
    private String savedSyncId = "";
    private boolean inviteQrExpanded;
    private String invitePayload = "";

    public WebDavSyncDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_webdav_sync;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogWebdavSyncBinding.bind(getPopupImplView());
        mBinding.switchEnable.setChecked(Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false));
        mBinding.switchWifiOnly.setChecked(Hawk.get(HawkConfig.WEBDAV_WIFI_ONLY, true));
        mBinding.etUrl.setText(Hawk.get(HawkConfig.WEBDAV_SYNC_URL, ""));
        savedSyncId = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        if (!WebDavSyncId.isValid(savedSyncId)) {
            savedSyncId = WebDavSyncId.get();
        }
        mBinding.etSyncId.setText(savedSyncId);
        intervalIndex = Hawk.get(HawkConfig.WEBDAV_SYNC_INTERVAL, 0);
        refreshIntervalLabel();
        refreshStatus();
        refreshInviteSection();
        refreshDeviceCountIfStale();

        try {
            WebDavCredentialStore.Credentials credentials = credentialStore.load();
            if (credentials != null) {
                mBinding.etUsername.setText(credentials.username);
                mBinding.etPassword.setText(credentials.password);
            }
        } catch (Exception ignored) {
        }

        mBinding.btnGenerateSyncId.setOnClickListener(v ->
                mBinding.etSyncId.setText(WebDavSyncId.generate()));
        mBinding.btnToggleInvite.setOnClickListener(v -> toggleInviteQr());
        mBinding.btnCopyInvite.setOnClickListener(v -> {
            String url = textOf(mBinding.etUrl);
            String syncId = textOf(mBinding.etSyncId);
            if (!TextUtils.isEmpty(url) && WebDavSyncId.isValid(syncId)) {
                WebDavSyncUiHelper.copyInvite(getContext(), url, syncId);
            } else {
                ToastUtils.showShort("请先填写 WebDAV 地址与有效空间 ID");
            }
        });
        mBinding.llInterval.setOnClickListener(v -> showIntervalPicker());
        mBinding.btnCancel.setOnClickListener(v -> dismiss());
        mBinding.btnSave.setOnClickListener(v -> save(false));
        mBinding.btnTest.setOnClickListener(v -> {
            if (!persistForAction()) return;
            mBinding.btnTest.setEnabled(false);
            io.execute(() -> {
                try {
                    new WebDavSyncCoordinator().testConnection().get();
                    post(() -> {
                        ToastUtils.showShort("连接成功");
                        mBinding.btnTest.setEnabled(true);
                        refreshStatus();
                        refreshInviteSection();
                    });
                } catch (Exception e) {
                    post(() -> {
                        ToastUtils.showShort("连接失败: " + WebDavSyncUiHelper.shortError(e));
                        mBinding.btnTest.setEnabled(true);
                    });
                }
            });
        });
        mBinding.btnSyncNow.setOnClickListener(v -> {
            if (!persistForAction()) return;
            if (!Hawk.get(HawkConfig.WEBDAV_SYNC_ENABLED, false)) {
                ToastUtils.showShort("请先启用同步");
                return;
            }
            runSync();
        });
    }

    private void toggleInviteQr() {
        if (TextUtils.isEmpty(invitePayload)) {
            ToastUtils.showShort("请先填写 WebDAV 地址与有效空间 ID");
            return;
        }
        inviteQrExpanded = !inviteQrExpanded;
        if (inviteQrExpanded) {
            Bitmap bitmap = QRCodeGen.generateBitmap(invitePayload, 420, 420, 1);
            mBinding.ivInviteQr.setImageBitmap(bitmap);
            mBinding.llInviteQr.setVisibility(View.VISIBLE);
            mBinding.btnToggleInvite.setText("收起二维码");
        } else {
            mBinding.llInviteQr.setVisibility(View.GONE);
            mBinding.btnToggleInvite.setText("显示邀请二维码");
        }
    }

    private void runSync() {
        mBinding.btnSyncNow.setEnabled(false);
        mBinding.tvStatus.setText("正在同步…");
        WebDavSyncUiHelper.syncNow(getContext(), new WebDavSyncUiHelper.SyncCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onSuccess(WebDavSyncCoordinator.SyncResult result) {
                post(() -> {
                    ToastUtils.showShort("同步完成");
                    mBinding.btnSyncNow.setEnabled(true);
                    refreshStatus();
                    refreshInviteSection();
                });
            }

            @Override
            public void onError(String message) {
                post(() -> {
                    ToastUtils.showShort("同步失败: " + message);
                    mBinding.btnSyncNow.setEnabled(true);
                    refreshStatus();
                });
            }
        });
    }

    private boolean save(boolean keepOpen) {
        return persist(true, keepOpen);
    }

    private boolean persistForAction() {
        return persist(false, true);
    }

    private boolean persist(boolean confirmSpaceChange, boolean keepOpen) {
        String url = textOf(mBinding.etUrl);
        String username = textOf(mBinding.etUsername);
        String password = textOf(mBinding.etPassword);
        String syncId = WebDavSyncId.normalize(textOf(mBinding.etSyncId));
        boolean enabled = mBinding.switchEnable.isChecked();
        if (enabled && TextUtils.isEmpty(url)) {
            ToastUtils.showShort("请填写 WebDAV 地址");
            return false;
        }
        if (enabled && !WebDavSyncId.isValid(syncId)) {
            ToastUtils.showShort("同步空间 ID 无效");
            return false;
        }
        if (confirmSpaceChange && !TextUtils.isEmpty(savedSyncId)
                && !savedSyncId.equals(syncId) && enabled) {
            new XPopup.Builder(getContext())
                    .asConfirm("切换同步空间",
                            "将同步到新空间，旧空间数据仍保留在网盘。是否继续？",
                            () -> {
                                applyPersist(url, username, password, syncId, enabled);
                                if (!keepOpen) {
                                    ToastUtils.showShort("已保存");
                                    dismiss();
                                }
                            })
                    .show();
            return false;
        }
        applyPersist(url, username, password, syncId, enabled);
        if (!keepOpen) {
            ToastUtils.showShort("已保存");
            dismiss();
        }
        return true;
    }

    private void applyPersist(String url, String username, String password,
                              String syncId, boolean enabled) {
        try {
            credentialStore.save(username, password);
        } catch (Exception e) {
            ToastUtils.showShort("凭据保存失败");
            return;
        }
        if (!savedSyncId.equals(syncId)) {
            Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, 0);
            Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, 0L);
        }
        Hawk.put(HawkConfig.WEBDAV_SYNC_URL, url);
        Hawk.put(HawkConfig.WEBDAV_SYNC_ID, syncId);
        Hawk.put(HawkConfig.WEBDAV_SYNC_ENABLED, enabled);
        Hawk.put(HawkConfig.WEBDAV_WIFI_ONLY, mBinding.switchWifiOnly.isChecked());
        Hawk.put(HawkConfig.WEBDAV_SYNC_INTERVAL, intervalIndex);
        savedSyncId = syncId;
        WebDavSyncScheduler.applySettings();
        refreshInviteSection();
        refreshStatus();
    }

    private void showIntervalPicker() {
        ArrayList<String> labels = new ArrayList<>(Arrays.asList(WebDavSyncScheduler.intervalLabels()));
        SelectDialog<String> dialog = new SelectDialog<>(getContext());
        dialog.setTip("同步频率");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<String>() {
            @Override
            public void click(String value, int pos) {
                intervalIndex = pos;
                refreshIntervalLabel();
            }

            @Override
            public String getDisplay(String name) {
                return name == null ? "" : name;
            }
        }, SelectDialogAdapter.stringDiff, labels, intervalIndex);
        dialog.show();
    }

    private void refreshIntervalLabel() {
        String[] labels = WebDavSyncScheduler.intervalLabels();
        if (intervalIndex < 0 || intervalIndex >= labels.length) intervalIndex = 0;
        mBinding.tvInterval.setText(labels[intervalIndex]);
    }

    private void refreshStatus() {
        String summary = WebDavSyncUiHelper.formatStatusSummary();
        String deviceId = WebDavDeviceId.get();
        if (deviceId.length() > 8) {
            deviceId = deviceId.substring(0, 8) + "…";
        }
        mBinding.tvStatus.setText(summary + " · 本机 " + deviceId);
    }

    private void refreshInviteSection() {
        String url = textOf(mBinding.etUrl);
        String syncId = textOf(mBinding.etSyncId);
        if (TextUtils.isEmpty(url) || !WebDavSyncId.isValid(syncId)) {
            invitePayload = "";
            inviteQrExpanded = false;
            mBinding.llInvite.setVisibility(View.GONE);
            mBinding.llInviteQr.setVisibility(View.GONE);
            return;
        }
        invitePayload = WebDavSpaceInvite.encode(url, syncId);
        mBinding.llInvite.setVisibility(View.VISIBLE);
        if (!inviteQrExpanded) {
            mBinding.llInviteQr.setVisibility(View.GONE);
            mBinding.btnToggleInvite.setText("显示邀请二维码");
        }
    }

    private void refreshDeviceCountIfStale() {
        WebDavSyncUiHelper.refreshDeviceCount(false, count ->
                post(this::refreshStatus));
    }

    private static String textOf(android.widget.EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
