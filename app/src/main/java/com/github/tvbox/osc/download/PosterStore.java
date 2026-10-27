package com.github.tvbox.osc.download;

import android.graphics.Bitmap;
import android.util.Log;

import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.squareup.picasso.Picasso;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PosterStore {
    private static final String TAG = "PosterStore";
    private static final int MAX_WIDTH = 480;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private PosterStore() {
    }

    static File existing(String seriesKey) {
        if (seriesKey == null || seriesKey.isEmpty()) return null;
        File file = DownloadPaths.posterFile(seriesKey);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    static void ensure(final String seriesKey, final String pic) {
        if (seriesKey == null || seriesKey.isEmpty() || pic == null || pic.trim().isEmpty()) return;
        if (existing(seriesKey) != null) return;
        if (FAILED.contains(seriesKey) || !PENDING.add(seriesKey)) return;
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    save(DownloadPaths.posterFile(seriesKey), pic.trim());
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_DOWNLOAD_UPDATE));
                } catch (Throwable e) {
                    Log.w(TAG, "save poster failed: " + pic, e);
                    FAILED.add(seriesKey);
                } finally {
                    PENDING.remove(seriesKey);
                }
            }
        });
    }

    static void delete(String seriesKey) {
        if (seriesKey == null || seriesKey.isEmpty()) return;
        FAILED.remove(seriesKey);
        File file = DownloadPaths.posterFile(seriesKey);
        if (file.exists()) file.delete();
    }

    static void deleteAll() {
        FAILED.clear();
        DownloadPaths.deleteRecursive(DownloadPaths.posterDir());
    }

    private static void save(File target, String pic) throws IOException {
        Bitmap bitmap = Picasso.get()
                .load(DefaultConfig.checkReplaceProxy(pic))
                .resize(MAX_WIDTH, 0)
                .onlyScaleDown()
                .get();
        if (bitmap == null) throw new IOException("empty poster");
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("无法创建封面目录");
        File tmp = new File(target.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("compress failed");
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("rename failed");
        }
    }
}
