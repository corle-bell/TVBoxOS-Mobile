package com.github.tvbox.osc.download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DownloadSeries {
    public String key;
    public String name;
    public int total;
    public int done;
    public int active;
    public int failed;
    public int progress;
    public String pic;

    public static List<DownloadSeries> group(List<DownloadTask> tasks) {
        Map<String, DownloadSeries> map = new LinkedHashMap<>();
        Map<String, Integer> progressSum = new LinkedHashMap<>();
        for (DownloadTask task : tasks) {
            String key = task.groupKey();
            DownloadSeries series = map.get(key);
            if (series == null) {
                series = new DownloadSeries();
                series.key = key;
                series.name = task.groupName();
                map.put(key, series);
                progressSum.put(key, 0);
            }
            series.total++;
            if (series.pic == null && task.seriesPic != null && !task.seriesPic.isEmpty()) {
                series.pic = task.seriesPic;
            }
            if (task.status == DownloadTask.DONE) {
                series.done++;
            } else if (task.status == DownloadTask.RUNNING || task.status == DownloadTask.WAITING) {
                series.active++;
            } else if (task.status == DownloadTask.FAILED) {
                series.failed++;
            }
            int p = task.status == DownloadTask.DONE ? 100 : Math.max(task.progress, 0);
            progressSum.put(key, progressSum.get(key) + p);
        }
        List<DownloadSeries> result = new ArrayList<>(map.values());
        for (DownloadSeries series : result) {
            series.progress = series.total == 0 ? 0 : progressSum.get(series.key) / series.total;
        }
        return result;
    }
}
