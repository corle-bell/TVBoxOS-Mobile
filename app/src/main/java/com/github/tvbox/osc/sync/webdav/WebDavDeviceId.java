package com.github.tvbox.osc.sync.webdav;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.UUID;

public final class WebDavDeviceId {
    private WebDavDeviceId() {
    }

    public static synchronized String get() {
        String value = Hawk.get(HawkConfig.WEBDAV_DEVICE_ID, "");
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString();
            Hawk.put(HawkConfig.WEBDAV_DEVICE_ID, value);
        }
        return value;
    }
}
