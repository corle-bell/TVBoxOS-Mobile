package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogWebdavSpaceWizardBinding;
import com.github.tvbox.osc.sync.webdav.WebDavCredentialStore;
import com.github.tvbox.osc.sync.webdav.WebDavSpaceInvite;
import com.github.tvbox.osc.sync.webdav.WebDavSyncCoordinator;
import com.github.tvbox.osc.sync.webdav.WebDavSyncId;
import com.github.tvbox.osc.sync.webdav.WebDavSyncScheduler;
import com.github.tvbox.osc.sync.webdav.WebDavSyncUiHelper;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.lxj.xpopup.core.CenterPopupView;
import com.orhanobut.hawk.Hawk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebDavSpaceWizardDialog extends CenterPopupView {
    public static final int MODE_CREATE = 0;
    public static final int MODE_JOIN = 1;

    private DialogWebdavSpaceWizardBinding mBinding;
    private final WebDavCredentialStore credentialStore = new WebDavCredentialStore();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final int mode;
    private int step;
    private Runnable onCompleted;

    public WebDavSpaceWizardDialog(@NonNull Context context, int mode) {
        super(context);
        this.mode = mode;
    }

    public WebDavSpaceWizardDialog setOnCompleted(Runnable onCompleted) {
        this.onCompleted = onCompleted;
        return this;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_webdav_space_wizard;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogWebdavSpaceWizardBinding.bind(getPopupImplView());
        mBinding.tvWizardTitle.setText(mode == MODE_CREATE ? "创建同步空间" : "加入已有空间");
        mBinding.btnGenerateSyncId.setOnClickListener(v ->
                mBinding.etSyncId.setText(WebDavSyncId.generate()));
        mBinding.btnBack.setOnClickListener(v -> goBack());
        mBinding.btnNext.setOnClickListener(v -> goNext());
        mBinding.btnCopyInvite.setOnClickListener(v -> {
            String url = textOf(mBinding.etUrl);
            String syncId = textOf(mBinding.etSyncId);
            if (!TextUtils.isEmpty(url) && WebDavSyncId.isValid(syncId)) {
                WebDavSyncUiHelper.copyInvite(getContext(), url, syncId);
            }
        });
        prefillCredentials();
        if (mode == MODE_CREATE) {
            mBinding.etSyncId.setText(WebDavSyncId.generate());
        }
        step = 1;
        refreshStepUi();
    }

    private void prefillCredentials() {
        mBinding.etUrl.setText(Hawk.get(HawkConfig.WEBDAV_SYNC_URL, ""));
        try {
            WebDavCredentialStore.Credentials credentials = credentialStore.load();
            if (credentials != null) {
                mBinding.etUsername.setText(credentials.username);
                mBinding.etPassword.setText(credentials.password);
                mBinding.etJoinUsername.setText(credentials.username);
                mBinding.etJoinPassword.setText(credentials.password);
            }
        } catch (Exception ignored) {
        }
        String syncId = Hawk.get(HawkConfig.WEBDAV_SYNC_ID, "");
        if (WebDavSyncId.isValid(syncId)) {
            mBinding.etSyncId.setText(syncId);
        }
    }

    private void goBack() {
        if (step <= 1) {
            dismiss();
            return;
        }
        step--;
        refreshStepUi();
    }

    private void goNext() {
        if (mode == MODE_CREATE) {
            handleCreateNext();
        } else {
            handleJoinNext();
        }
    }

    private void handleCreateNext() {
        if (step == 4) {
            dismiss();
            return;
        }
        if (step == 1) {
            if (!validateCreateCredentials()) return;
            step = 2;
            refreshStepUi();
            return;
        }
        if (step == 2) {
            String syncId = WebDavSyncId.normalize(textOf(mBinding.etSyncId));
            if (!WebDavSyncId.isValid(syncId)) {
                ToastUtils.showShort("同步空间 ID 无效");
                return;
            }
            mBinding.etSyncId.setText(syncId);
            persistConfig(false);
            testAndAdvanceCreate(syncId);
            return;
        }
        if (step == 3) {
            Hawk.put(HawkConfig.WEBDAV_SYNC_ENABLED, true);
            WebDavSyncScheduler.applySettings();
            syncAndFinish(true);
            return;
        }
    }

    private void handleJoinNext() {
        if (step == 4) {
            dismiss();
            return;
        }
        if (step == 1) {
            WebDavSpaceInvite invite = WebDavSpaceInvite.parse(textOf(mBinding.etInvite));
            if (invite == null || !invite.isValid()) {
                ToastUtils.showShort("邀请信息无效");
                return;
            }
            mBinding.etUrl.setText(invite.url);
            mBinding.etSyncId.setText(invite.syncId);
            mBinding.tvJoinUrl.setText("WebDAV: " + invite.url);
            mBinding.tvJoinSyncId.setText("空间 ID: " + invite.syncId);
            step = 2;
            refreshStepUi();
            return;
        }
        if (step == 2) {
            if (TextUtils.isEmpty(textOf(mBinding.etJoinPassword))) {
                ToastUtils.showShort("请填写 WebDAV 密码");
                return;
            }
            persistJoinConfig();
            testAndAdvanceJoin();
            return;
        }
        if (step == 3) {
            Hawk.put(HawkConfig.WEBDAV_SYNC_ENABLED, true);
            WebDavSyncScheduler.applySettings();
            syncAndFinish(false);
            return;
        }
    }

    private void testAndAdvanceCreate(String syncId) {
        setBusy(true, "正在测试连接…");
        io.execute(() -> {
            try {
                new WebDavSyncCoordinator().testConnection().get();
                post(() -> {
                    setBusy(false, "连接成功");
                    step = 3;
                    refreshStepUi();
                    showInviteQr(textOf(mBinding.etUrl), syncId);
                });
            } catch (Exception e) {
                post(() -> {
                    setBusy(false, "连接失败: " + WebDavSyncUiHelper.shortError(e));
                });
            }
        });
    }

    private void testAndAdvanceJoin() {
        setBusy(true, "正在测试连接…");
        io.execute(() -> {
            try {
                new WebDavSyncCoordinator().testConnection().get();
                post(() -> {
                    setBusy(false, "连接成功");
                    step = 3;
                    refreshStepUi();
                    mBinding.ivInviteQr.setVisibility(View.GONE);
                    mBinding.btnCopyInvite.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                post(() -> setBusy(false, "连接失败: " + WebDavSyncUiHelper.shortError(e)));
            }
        });
    }

    private void syncAndFinish(boolean showQr) {
        setBusy(true, "正在同步…");
        io.execute(() -> {
            try {
                WebDavSyncCoordinator.SyncResult result =
                        new WebDavSyncCoordinator().sync().get();
                post(() -> {
                    setBusy(false, "");
                    step = 4;
                    refreshStepUi();
                    if (showQr) {
                        showInviteQr(textOf(mBinding.etUrl), textOf(mBinding.etSyncId));
                    }
                    mBinding.tvFinishStatus.setText("同步完成 · 当前空间 "
                            + result.deviceCount + " 台设备");
                    if (onCompleted != null) onCompleted.run();
                });
            } catch (Exception e) {
                post(() -> setBusy(false, "同步失败: " + WebDavSyncUiHelper.shortError(e)));
            }
        });
    }

    private void refreshStepUi() {
        mBinding.llCreateStep1.setVisibility(View.GONE);
        mBinding.llCreateStep2.setVisibility(View.GONE);
        mBinding.llJoinStep1.setVisibility(View.GONE);
        mBinding.llJoinStep2.setVisibility(View.GONE);
        mBinding.llFinish.setVisibility(View.GONE);
        mBinding.btnBack.setText(step <= 1 ? "取消" : "上一步");

        if (mode == MODE_CREATE) {
            if (step == 1) {
                mBinding.llCreateStep1.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 1/3 · 填写 WebDAV 凭据");
                mBinding.btnNext.setText("下一步");
            } else if (step == 2) {
                mBinding.llCreateStep2.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 2/3 · 设置同步空间 ID 并测试");
                mBinding.btnNext.setText("测试连接");
            } else if (step == 3) {
                mBinding.llFinish.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 3/3 · 分享邀请并启用同步");
                mBinding.btnNext.setText("启用并同步");
                mBinding.tvFinishStatus.setText("启用后将立即上传本机历史");
            } else {
                mBinding.llFinish.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("完成");
                mBinding.btnBack.setVisibility(View.GONE);
                mBinding.btnNext.setText("完成");
            }
        } else {
            mBinding.ivInviteQr.setVisibility(View.GONE);
            mBinding.btnCopyInvite.setVisibility(View.GONE);
            if (step == 1) {
                mBinding.llJoinStep1.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 1/3 · 粘贴邀请信息");
                mBinding.btnNext.setText("下一步");
            } else if (step == 2) {
                mBinding.llJoinStep2.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 2/3 · 填写密码并测试");
                mBinding.btnNext.setText("测试连接");
            } else if (step == 3) {
                mBinding.llFinish.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("步骤 3/3 · 拉取远端数据");
                mBinding.btnNext.setText("立即同步");
                mBinding.tvFinishStatus.setText("同步后将合并其它设备的历史");
            } else {
                mBinding.llFinish.setVisibility(View.VISIBLE);
                mBinding.tvStepHint.setText("完成");
                mBinding.btnBack.setVisibility(View.GONE);
                mBinding.btnNext.setText("完成");
            }
        }
    }

    private void showInviteQr(String url, String syncId) {
        if (TextUtils.isEmpty(url) || !WebDavSyncId.isValid(syncId)) {
            mBinding.llFinish.setVisibility(View.GONE);
            return;
        }
        mBinding.ivInviteQr.setVisibility(View.VISIBLE);
        mBinding.btnCopyInvite.setVisibility(View.VISIBLE);
        String payload = WebDavSpaceInvite.encode(url, syncId);
        ImageView iv = mBinding.ivInviteQr;
        iv.setImageBitmap(QRCodeGen.generateBitmap(payload, 480, 480, 1));
    }

    private boolean validateCreateCredentials() {
        if (TextUtils.isEmpty(textOf(mBinding.etUrl))) {
            ToastUtils.showShort("请填写 WebDAV 地址");
            return false;
        }
        if (TextUtils.isEmpty(textOf(mBinding.etPassword))) {
            ToastUtils.showShort("请填写 WebDAV 密码");
            return false;
        }
        return true;
    }

    private void persistConfig(boolean enable) {
        Hawk.put(HawkConfig.WEBDAV_SYNC_URL, textOf(mBinding.etUrl).trim());
        Hawk.put(HawkConfig.WEBDAV_SYNC_ID, WebDavSyncId.normalize(textOf(mBinding.etSyncId)));
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, 0);
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, 0L);
        if (enable) Hawk.put(HawkConfig.WEBDAV_SYNC_ENABLED, true);
        try {
            credentialStore.save(textOf(mBinding.etUsername), textOf(mBinding.etPassword));
        } catch (Exception e) {
            ToastUtils.showShort("凭据保存失败");
        }
        WebDavSyncScheduler.applySettings();
    }

    private void persistJoinConfig() {
        Hawk.put(HawkConfig.WEBDAV_SYNC_URL, textOf(mBinding.etUrl).trim());
        Hawk.put(HawkConfig.WEBDAV_SYNC_ID, WebDavSyncId.normalize(textOf(mBinding.etSyncId)));
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT, 0);
        Hawk.put(HawkConfig.WEBDAV_SPACE_DEVICE_COUNT_AT, 0L);
        try {
            credentialStore.save(textOf(mBinding.etJoinUsername), textOf(mBinding.etJoinPassword));
        } catch (Exception e) {
            ToastUtils.showShort("凭据保存失败");
        }
    }

    private void setBusy(boolean busy, String status) {
        mBinding.btnNext.setEnabled(!busy);
        mBinding.btnBack.setEnabled(!busy);
        if (!TextUtils.isEmpty(status)) mBinding.tvStatus.setText(status);
    }

    private static String textOf(android.widget.EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
