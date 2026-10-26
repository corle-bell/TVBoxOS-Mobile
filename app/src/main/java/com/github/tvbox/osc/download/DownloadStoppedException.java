package com.github.tvbox.osc.download;

class DownloadStoppedException extends Exception {
    DownloadStoppedException() {
        super("下载已暂停");
    }
}
