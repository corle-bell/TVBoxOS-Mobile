package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.activity.MovieFoldersActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.SubscriptionActivity;
import com.github.tvbox.osc.util.DialogHelper;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import java.util.Arrays;
import java.util.List;

public class TvMyFragment extends BaseLazyFragment {

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_tv_my;
    }

    @Override
    protected void init() {
        findViewById(R.id.tvVersion).setVisibility(View.VISIBLE);
        ((android.widget.TextView) findViewById(R.id.tvVersion))
                .setText("v" + AppUtils.getAppVersionName());

        findViewById(R.id.addrPlay).setOnClickListener(v ->
                DialogHelper.showInput(mActivity, "推送播放", "地址", text -> {
                    if (!TextUtils.isEmpty(text)) {
                        Intent newIntent = new Intent(mContext, DetailActivity.class);
                        newIntent.putExtra("id", text);
                        newIntent.putExtra("sourceKey", "push_agent");
                        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(newIntent);
                    }
                }));

        findViewById(R.id.tvLive).setOnClickListener(v -> jumpActivity(LiveActivity.class));
        findViewById(R.id.tvSetting).setOnClickListener(v -> jumpActivity(SettingActivity.class));
        findViewById(R.id.tvHistory).setOnClickListener(v -> jumpActivity(HistoryActivity.class));
        findViewById(R.id.tvFavorite).setOnClickListener(v -> jumpActivity(CollectActivity.class));
        findViewById(R.id.tvLocal).setOnClickListener(v -> {
            if (!XXPermissions.isGranted(mContext, Permission.MANAGE_EXTERNAL_STORAGE)) {
                XXPermissions.with(this)
                        .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                        .request(new OnPermissionCallback() {
                            @Override
                            public void onGranted(List<String> permissions, boolean all) {
                                if (all) jumpActivity(MovieFoldersActivity.class);
                            }

                            @Override
                            public void onDenied(List<String> permissions, boolean never) {
                                ToastUtils.showShort("需要存储权限");
                            }
                        });
            } else {
                jumpActivity(MovieFoldersActivity.class);
            }
        });
        findViewById(R.id.llSubscription).setOnClickListener(v -> jumpActivity(SubscriptionActivity.class));
        findViewById(R.id.llAbout).setOnClickListener(v ->
                com.github.tvbox.osc.util.DialogHelper.showAbout(mActivity));
    }

    private boolean isPush(String text) {
        return !TextUtils.isEmpty(text) && Arrays.asList("smb", "http", "https", "thunder",
                "magnet", "ed2k", "mitv", "jianpian").contains(Uri.parse(text).getScheme());
    }
}
