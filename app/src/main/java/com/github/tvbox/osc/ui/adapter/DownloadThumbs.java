package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.download.VideoDownloadManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.squareup.picasso.Picasso;

import java.io.File;

final class DownloadThumbs {
    private DownloadThumbs() {
    }

    static void size(ImageView iv, int widthDp, int heightDp) {
        ViewGroup.LayoutParams lp = iv.getLayoutParams();
        int w = ConvertUtils.dp2px(widthDp);
        int h = ConvertUtils.dp2px(heightDp);
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            iv.setLayoutParams(lp);
        }
    }

    static void poster(ImageView iv, String seriesKey, String pic) {
        File local = VideoDownloadManager.get().posterFile(seriesKey);
        String key = local != null ? "file:" + local.getPath() + ":" + local.lastModified()
                : TextUtils.isEmpty(pic) ? "none" : "pic:" + pic;
        if (key.equals(iv.getTag(R.id.ivThumb))) return;
        iv.setTag(R.id.ivThumb, key);
        Picasso.get().cancelRequest(iv);
        if (local != null) {
            Picasso.get()
                    .load(local)
                    .placeholder(R.drawable.iv_video)
                    .error(R.drawable.iv_video)
                    .fit()
                    .centerCrop()
                    .into(iv);
        } else if (!TextUtils.isEmpty(pic)) {
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(pic))
                    .placeholder(R.drawable.img_loading_placeholder)
                    .error(R.drawable.iv_video)
                    .fit()
                    .centerCrop()
                    .into(iv);
        } else {
            iv.setImageResource(R.drawable.iv_video);
        }
    }
}
