package com.github.tvbox.osc.share;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.sync.webdav.WebDavSyncUiHelper;
import com.github.tvbox.osc.ui.activity.DetailActivity;

public final class PlayShareHelper {
    private static final String CLIP_LABEL = "tvbox-share-play";

    private PlayShareHelper() {
    }

    public static void copyShare(Context context, PlaySharePayload payload) {
        if (context == null || payload == null || !payload.isValid()) {
            ToastUtils.showShort("无法分享当前内容");
            return;
        }
        String json = PlaySharePayload.encode(payload);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, json));
            ToastUtils.showShort("观看信息已复制");
        }
    }

    public static PlaySharePayload readFromClipboard(Context context) {
        return PlaySharePayload.parse(WebDavSyncUiHelper.readClipboard(context));
    }

    public static boolean openFromClipboard(Context context) {
        PlaySharePayload payload = readFromClipboard(context);
        if (payload == null) {
            ToastUtils.showShort("粘贴板无有效观看分享");
            return false;
        }
        return openPayload(context, payload);
    }

    public static boolean openPayload(Context context, PlaySharePayload payload) {
        if (context == null || payload == null || !payload.isValid()) {
            ToastUtils.showShort("分享内容无效");
            return false;
        }
        SourceBean source = ApiConfig.get().getSource(payload.sourceKey);
        if (source == null) {
            ToastUtils.showShort("未配置影视源: " + payload.sourceKey);
            return false;
        }
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra("id", payload.vodId);
        intent.putExtra("sourceKey", payload.sourceKey);
        intent.putExtra(IntentKey.SHARE_PLAY_FLAG, payload.playFlag);
        intent.putExtra(IntentKey.SHARE_PLAY_INDEX, payload.playIndex);
        intent.putExtra(IntentKey.SHARE_POSITION_MS, payload.positionMs);
        if (!TextUtils.isEmpty(payload.playerCfg)) {
            intent.putExtra(IntentKey.SHARE_PLAYER_CFG, payload.playerCfg);
        }
        intent.putExtra(IntentKey.SHARE_AUTO_PLAY, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        return true;
    }
}
