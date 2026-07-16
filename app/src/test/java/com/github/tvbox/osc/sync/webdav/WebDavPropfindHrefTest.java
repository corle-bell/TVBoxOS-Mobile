package com.github.tvbox.osc.sync.webdav;

import org.junit.Test;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class WebDavPropfindHrefTest {
    private static final String SYNC_ID = "tvbox-7aful4st";
    private static final String DEVICE_A = "3cc94e35-d2ac-4277-8b69-170e5c731698.json";
    private static final String DEVICE_B = "68952b7d-dfc6-4658-813a-a123f6073a4d.json";

    @Test
    public void resolvesCanonicalDevicesPath() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/webdav/");
        HttpUrl devicesRoot = devicesRoot(root);
        HttpUrl resolved = WebDavPropfindHref.resolveSnapshot(
                "/webdav/tvbox-sync/v1/spaces/" + SYNC_ID + "/devices/" + DEVICE_A,
                root, devicesRoot, SYNC_ID);
        assertNotNull(resolved);
        assertEquals(devicesRoot.newBuilder().addPathSegment(DEVICE_A).build().toString(),
                resolved.toString());
    }

    @Test
    public void resolvesHrefMissingConfiguredPrefix() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/webdav/");
        HttpUrl devicesRoot = devicesRoot(root);
        HttpUrl resolved = WebDavPropfindHref.resolveSnapshot(
                "/tvbox-sync/v1/spaces/" + SYNC_ID + "/devices/" + DEVICE_B,
                root, devicesRoot, SYNC_ID);
        assertNotNull(resolved);
        assertEquals(devicesRoot.newBuilder().addPathSegment(DEVICE_B).build().toString(),
                resolved.toString());
    }

    @Test
    public void resolvesRelativeFileName() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/dav/");
        HttpUrl devicesRoot = devicesRoot(root);
        HttpUrl resolved = WebDavPropfindHref.resolveSnapshot(
                DEVICE_A, root, devicesRoot, SYNC_ID);
        assertNotNull(resolved);
        assertEquals(devicesRoot.newBuilder().addPathSegment(DEVICE_A).build().toString(),
                resolved.toString());
    }

    @Test
    public void resolvesFullUrl() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/dav/");
        HttpUrl devicesRoot = devicesRoot(root);
        HttpUrl resolved = WebDavPropfindHref.resolveSnapshot(
                "https://dav.example.com/tvbox-sync/v1/spaces/" + SYNC_ID
                        + "/devices/" + DEVICE_A,
                root, devicesRoot, SYNC_ID);
        assertNotNull(resolved);
        assertEquals(devicesRoot.newBuilder().addPathSegment(DEVICE_A).build().toString(),
                resolved.toString());
    }

    @Test
    public void ignoresDevicesCollectionHref() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/dav/");
        HttpUrl devicesRoot = devicesRoot(root);
        assertNull(WebDavPropfindHref.resolveSnapshot(
                "/dav/tvbox-sync/v1/spaces/" + SYNC_ID + "/devices/",
                root, devicesRoot, SYNC_ID));
    }

    @Test
    public void ignoresTemporarySnapshot() {
        HttpUrl root = HttpUrl.parse("https://dav.example.com/dav/");
        HttpUrl devicesRoot = devicesRoot(root);
        assertNull(WebDavPropfindHref.resolveSnapshot(
                "/dav/tvbox-sync/v1/spaces/" + SYNC_ID
                        + "/devices/" + DEVICE_A + ".tmp-123",
                root, devicesRoot, SYNC_ID));
    }

    private static HttpUrl devicesRoot(HttpUrl root) {
        return root.newBuilder()
                .addPathSegment("tvbox-sync")
                .addPathSegment("v1")
                .addPathSegment("spaces")
                .addPathSegment(SYNC_ID)
                .addPathSegment("devices")
                .build();
    }
}
