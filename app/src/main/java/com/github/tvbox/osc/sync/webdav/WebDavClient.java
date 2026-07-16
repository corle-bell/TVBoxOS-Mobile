package com.github.tvbox.osc.sync.webdav;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebDavClient {
    private static final String TAG = "WebDavSync";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private final OkHttpClient client;
    private final HttpUrl root;
    private final String authorization;

    public WebDavClient(String endpoint, String username, String password) {
        this(new OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build(),
                endpoint, username, password);
    }

    WebDavClient(OkHttpClient client, String endpoint, String username, String password) {
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null) throw new IllegalArgumentException("Invalid WebDAV endpoint");
        this.client = client;
        this.root = parsed.newBuilder()
                .username("")
                .password("")
                .query(null)
                .fragment(null)
                .build();
        this.authorization = Credentials.basic(username == null ? "" : username,
                password == null ? "" : password);
    }

    public void ensureCollections(String syncId) throws IOException {
        String space = WebDavSyncId.requireValid(syncId);
        mkcol(syncRoot());
        mkcol(versionRoot());
        mkcol(spacesRoot());
        mkcol(spaceRoot(space));
        mkcol(spaceDevicesRoot(space));
    }

    /** Verifies credentials and that the sync space can be created/listed. */
    public void testConnection(String syncId) throws IOException {
        ensureCollections(syncId);
        listDeviceSnapshots(syncId);
    }

    public int countDeviceSnapshots(String syncId) throws IOException {
        return listDeviceSnapshots(syncId).size();
    }

    public List<HttpUrl> listDeviceSnapshots(String syncId) throws IOException {
        String space = WebDavSyncId.requireValid(syncId);
        HttpUrl devicesRoot = spaceDevicesRoot(space);
        Set<HttpUrl> unique = new LinkedHashSet<>();
        collectSnapshots(unique, propfindHrefs(devicesRoot), space, devicesRoot);
        if (unique.isEmpty()) {
            HttpUrl slashRoot = collectionUrlWithSlash(devicesRoot);
            if (!slashRoot.equals(devicesRoot)) {
                collectSnapshots(unique, propfindHrefs(slashRoot), space, devicesRoot);
            }
        }
        List<HttpUrl> result = new ArrayList<>(unique);
        Log.d(TAG, "listDeviceSnapshots syncId=" + space + " count=" + result.size()
                + " root=" + devicesRoot);
        return result;
    }

    private List<String> propfindHrefs(HttpUrl collectionUrl) throws IOException {
        String body = execute("PROPFIND", collectionUrl,
                RequestBody.create(XML, "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>"),
                new String[]{"Depth", "1"}, 207);
        try {
            List<String> hrefs = WebDavPropfindParser.extractHrefs(body);
            Log.d(TAG, "PROPFIND hrefs=" + hrefs.size() + " url=" + collectionUrl);
            return hrefs;
        } catch (Exception e) {
            throw new IOException("Invalid WebDAV PROPFIND response", e);
        }
    }

    private void collectSnapshots(Set<HttpUrl> out, List<String> hrefs, String syncId,
                                  HttpUrl devicesRoot) {
        for (String href : hrefs) {
            HttpUrl url = WebDavPropfindHref.resolveSnapshot(href, root, devicesRoot, syncId);
            if (url != null) {
                out.add(url);
            } else {
                Log.d(TAG, "skip href=" + href);
            }
        }
    }

    private static HttpUrl collectionUrlWithSlash(HttpUrl dir) {
        String path = dir.encodedPath();
        if (path.endsWith("/")) return dir;
        return dir.newBuilder().encodedPath(path + "/").build();
    }

    public String get(HttpUrl url) throws IOException {
        return execute("GET", url, null, null, 200);
    }

    public void putSnapshot(String syncId, String deviceId, String json) throws IOException {
        HttpUrl devicesRoot = spaceDevicesRoot(WebDavSyncId.requireValid(syncId));
        HttpUrl destination = snapshotUrl(devicesRoot, deviceId);
        HttpUrl temporary = devicesRoot.newBuilder()
                .addPathSegment(deviceId + ".json.tmp-" + System.currentTimeMillis()).build();
        RequestBody body = RequestBody.create(JSON, json);
        boolean moved = false;
        try {
            put(temporary, body, null);
            int status = executeStatus("MOVE", temporary, null,
                    new String[]{"Destination", destination.toString(), "Overwrite", "T"});
            moved = status >= 200 && status < 300;
        } catch (IOException ignored) {
            // Some WebDAV servers do not support temporary resources or MOVE.
        }
        if (!moved) {
            String etag = null;
            try {
                etag = headEtag(destination);
            } catch (IOException ignored) {
                // Resource may not exist yet.
            }
            put(destination, RequestBody.create(JSON, json), etag);
            try {
                executeStatus("DELETE", temporary, null, null);
            } catch (IOException ignored) {
                // Best-effort cleanup; never log request headers or credentials.
            }
        }
    }

    private String headEtag(HttpUrl url) throws IOException {
        Request request = request("HEAD", url, null, null);
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404) return null;
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("WebDAV HEAD failed: HTTP " + response.code());
            }
            return response.header("ETag");
        }
    }

    private void put(HttpUrl url, RequestBody body, String etag) throws IOException {
        String[] headers = null;
        if (etag != null && !etag.isEmpty()) {
            headers = new String[]{"If-Match", etag};
        }
        int status = executeStatus("PUT", url, body, headers);
        if (status == 412 && etag != null) {
            status = executeStatus("PUT", url, body, null);
        }
        if (status < 200 || status >= 300) throw new IOException("WebDAV PUT failed: HTTP " + status);
    }

    private void mkcol(HttpUrl url) throws IOException {
        int status = executeStatus("MKCOL", url, null, null);
        if ((status < 200 || status >= 300) && status != 405) {
            throw new IOException("WebDAV MKCOL failed: HTTP " + status);
        }
    }

    private String execute(String method, HttpUrl url, RequestBody body, String[] headers,
                           int expectedStatus) throws IOException {
        Request request = request(method, url, body, headers);
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != expectedStatus) {
                throw new IOException("WebDAV " + method + " failed: HTTP " + response.code());
            }
            return response.body() == null ? "" : response.body().string();
        }
    }

    private int executeStatus(String method, HttpUrl url, RequestBody body, String[] headers)
            throws IOException {
        try (Response response = client.newCall(request(method, url, body, headers)).execute()) {
            return response.code();
        }
    }

    private Request request(String method, HttpUrl url, RequestBody body, String[] headers) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("Authorization", authorization)
                .method(method, body);
        if (headers != null) {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                builder.header(headers[i], headers[i + 1]);
            }
        }
        return builder.build();
    }

    private HttpUrl syncRoot() {
        return root.newBuilder().addPathSegment("tvbox-sync").build();
    }

    private HttpUrl versionRoot() {
        return syncRoot().newBuilder().addPathSegment("v1").build();
    }

    private HttpUrl spacesRoot() {
        return versionRoot().newBuilder().addPathSegment("spaces").build();
    }

    private HttpUrl spaceRoot(String syncId) {
        return spacesRoot().newBuilder().addPathSegment(syncId).build();
    }

    private HttpUrl spaceDevicesRoot(String syncId) {
        return spaceRoot(syncId).newBuilder().addPathSegment("devices").build();
    }

    private HttpUrl snapshotUrl(HttpUrl devicesRoot, String deviceId) {
        return devicesRoot.newBuilder().addPathSegment(deviceId + ".json").build();
    }
}
