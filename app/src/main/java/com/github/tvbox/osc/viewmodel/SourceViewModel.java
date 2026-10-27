package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.blankj.utilcode.util.ToastUtils;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
    }

    private static final long SPIDER_TIMEOUT_SEC = 15;
    /** 按用途分队列，避免慢的首页/分类请求堵住详情和播放 */
    private static final ExecutorService HOME_QUEUE = Executors.newSingleThreadExecutor(r -> new Thread(r, "spider-home"));
    private static final ExecutorService LIST_QUEUE = Executors.newSingleThreadExecutor(r -> new Thread(r, "spider-list"));
    private static final ExecutorService DETAIL_QUEUE = Executors.newSingleThreadExecutor(r -> new Thread(r, "spider-detail"));
    private static final ExecutorService SPIDER_WORKER = Executors.newCachedThreadPool(r -> new Thread(r, "spider-call"));

    private static final Gson GSON = new Gson();
    private static XStream sortXStream;
    private static XStream absXStream;

    private final AtomicInteger searchToken = new AtomicInteger();

    /**
     * 爬虫方法可能卡死，放到独立线程执行并限时，超时后调用方继续往下走
     */
    private static <T> T callWithTimeout(Callable<T> call) throws Exception {
        Future<T> future = SPIDER_WORKER.submit(call);
        try {
            return future.get(SPIDER_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
        }
    }

    /**
     * 在 OkGo 的后台线程里解析响应，主线程只接收结果
     */
    private abstract static class BodyCallback extends AbsCallback<String> {
        @Override
        public String convertResponse(okhttp3.Response response) throws Throwable {
            if (response.body() == null) throw new IllegalStateException("网络请求错误");
            String body = response.body().string();
            onBody(body);
            return body;
        }

        @Override
        public void onSuccess(Response<String> response) {
        }

        @Override
        public void onError(Response<String> response) {
            super.onError(response);
            onFailure();
        }

        abstract void onBody(String body) throws Throwable;

        abstract void onFailure();
    }

    /**
     * 开始新一轮搜索，之前批次的结果会被丢弃
     */
    public int nextSearchToken() {
        return searchToken.incrementAndGet();
    }

    // homeContent
    public void getSort(String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            HOME_QUEUE.execute(() -> {
                String sortJson = null;
                try {
                    sortJson = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean).homeContent(true));
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                postSortJson(sourceBean, sortJson);
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            AbsSortXml sortXml = type == 0 ? sortXml(sortResult, body) : sortJson(sortResult, body);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        sortXml.videoList = videos;
                                        sortResult.postValue(sortXml);
                                    }
                                });
                            } else {
                                sortResult.postValue(sortXml);
                            }
                        }

                        @Override
                        void onFailure() {
                            sortResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                .tag(sourceBean.getKey() + "_sort")
                .params("filter", "true")
                .execute(new BodyCallback() {
                    @Override
                    void onBody(String body) {
                        postSortJson(sourceBean, body);
                    }

                    @Override
                    void onFailure() {
                        sortResult.postValue(null);
                    }
                });
        } else {
            sortResult.postValue(null);
        }
    }

    private void postSortJson(SourceBean sourceBean, String sortJson) {
        if (sortJson == null) {
            sortResult.postValue(null);
            return;
        }
        AbsSortXml sortXml = sortJson(sortResult, sortJson);
        if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                sortXml.videoList = absXml.movie.videoList;
                sortResult.postValue(sortXml);
            } else {
                getHomeRecList(sourceBean, null, new HomeRecCallback() {
                    @Override
                    public void done(List<Movie.Video> videos) {
                        sortXml.videoList = videos;
                        sortResult.postValue(sortXml);
                    }
                });
            }
        } else {
            sortResult.postValue(sortXml);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            LIST_QUEUE.execute(() -> {
                try {
                    String content = callWithTimeout(() -> ApiConfig.get().getCSP(homeSourceBean)
                            .categoryContent(sortData.id, page + "", true, sortData.filterSelect));
                    json(listResult, content, homeSourceBean.getKey());
                } catch (Throwable th) {
                    th.printStackTrace();
                    listResult.postValue(null);
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            if (type == 0) {
                                xml(listResult, body, homeSourceBean.getKey());
                            } else {
                                json(listResult, body, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        void onFailure() {
                            listResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            String ext= "";
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
                    LOG.i(ext);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
            }
            OkGo.<String>get(homeSourceBean.getApi())
                .tag(homeSourceBean.getApi())
                .params("ac", "detail")
                .params("filter", "true")
                .params("t", sortData.id)
                .params("pg", page)
                .params("ext", ext)
                .execute(new BodyCallback() {
                    @Override
                    void onBody(String body) {
                        LOG.i(body);
                        json(listResult, body, homeSourceBean.getKey());
                    }

                    @Override
                    void onFailure() {
                        listResult.postValue(null);
                    }
                });
        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            HOME_QUEUE.execute(() -> {
                String sortJson = null;
                try {
                    sortJson = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean).homeVideoContent());
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                AbsXml absXml = sortJson == null ? null : json(null, sortJson, sourceBean.getKey());
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                    callback.done(absXml.movie.videoList);
                } else {
                    callback.done(null);
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            AbsXml absXml = sourceBean.getType() == 0
                                    ? xml(null, body, sourceBean.getKey())
                                    : json(null, body, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        void onFailure() {
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String id) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            DETAIL_QUEUE.execute(() -> {
                try {
                    List<String> ids = new ArrayList<>();
                    ids.add(id);
                    String content = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean).detailContent(ids));
                    json(detailResult, content, sourceBean.getKey());
                } catch (Throwable th) {
                    th.printStackTrace();
                    detailResult.postValue(null);
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id)
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            if (type == 0) {
                                xml(detailResult, body, sourceBean.getKey());
                            } else {
                                LOG.i(body);
                                json(detailResult, body, sourceBean.getKey());
                            }
                        }

                        @Override
                        void onFailure() {
                            detailResult.postValue(null);
                        }
                    });
        } else {
            detailResult.postValue(null);
        }
    }
    // searchContent
    public void getSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        int token = searchToken.get();
        if (type == 3) {
            try {
                String search = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean).searchContent(wd, false));
                json(searchResult, TextUtils.isEmpty(search) ? "" : search, sourceBean.getKey(), token);
            } catch (Throwable th) {
                th.printStackTrace();
                json(searchResult, "", sourceBean.getKey(), token);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("search")
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            if (type == 0) {
                                xml(searchResult, body, sourceBean.getKey(), token);
                            } else {
                                json(searchResult, body, sourceBean.getKey(), token);
                            }
                        }

                        @Override
                        void onFailure() {
                            postSearchEvent(RefreshEvent.TYPE_SEARCH_RESULT, null, token);
                        }
                    });
        }else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                .params("wd", wd)
                .params("ac" ,"detail")
                .params("quick" ,"false")
                .tag("search")
                .execute(new BodyCallback() {
                    @Override
                    void onBody(String body) {
                        LOG.i(body);
                        json(searchResult, body, sourceBean.getKey(), token);
                    }

                    @Override
                    void onFailure() {
                        postSearchEvent(RefreshEvent.TYPE_SEARCH_RESULT, null, token);
                    }
                });
        } else {
            postSearchEvent(RefreshEvent.TYPE_SEARCH_RESULT, null, token);
        }
    }

    private static void postSearchEvent(int type, AbsXml data, int token) {
        RefreshEvent event = new RefreshEvent(type, data);
        event.token = token;
        EventBus.getDefault().post(event);
    }
    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                String content = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean).searchContent(wd, true));
                json(quickSearchResult, content, sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
                postSearchEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null, 0);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new BodyCallback() {
                        @Override
                        void onBody(String body) {
                            if (type == 0) {
                                xml(quickSearchResult, body, sourceBean.getKey());
                            } else {
                                json(quickSearchResult, body, sourceBean.getKey());
                            }
                        }

                        @Override
                        void onFailure() {
                            postSearchEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null, 0);
                        }
                    });
        }else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                .params("wd", wd)
                .params("ac" ,"detail")
                .params("quick" ,"true")
                .tag("quick_search")
                .execute(new BodyCallback() {
                    @Override
                    void onBody(String body) {
                        LOG.i(body);
                        json(quickSearchResult, body, sourceBean.getKey());
                    }

                    @Override
                    void onFailure() {
                        postSearchEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null, 0);
                    }
                });
        } else {
            quickSearchResult.postValue(null);
        }
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int type = sourceBean.getType();
        if (type == 3) {
            DETAIL_QUEUE.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String json = callWithTimeout(() -> ApiConfig.get().getCSP(sourceBean)
                                .playerContent(playFlag, url, ApiConfig.get().getVipParseFlags()));
                        JSONObject result = new JSONObject(json);
                        result.put("key", url);
                        result.put("proKey", progressKey);
                        result.put("subtKey", subtitleKey);
                        if (!result.has("flag"))
                            result.put("flag", playFlag);
                        playResult.postValue(result);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        playResult.postValue(null);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(url) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", url);
                } else {
                    result.put("parse", 1);
                    result.put("url", url);
                }
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                playResult.postValue(result);
            } catch (Throwable th) {
                th.printStackTrace();
                playResult.postValue(null);
            }
        } else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                .params("play", url)
                .params("flag" ,playFlag)
                .tag("play")
                .execute(new BodyCallback() {
                    @Override
                    void onBody(String json) {
                        LOG.i(json);
                        try {
                            JSONObject result = new JSONObject(json);
                            result.put("key", url);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            playResult.postValue(result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            playResult.postValue(null);
                        }
                    }

                    @Override
                    void onFailure() {
                        playResult.postValue(null);
                    }
                });
        }else {
            playResult.postValue(null);
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = GSON.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            AbsSortXml data;
            synchronized (SourceViewModel.class) {
                if (sortXStream == null) {
                    sortXStream = new XStream(new DomDriver());//创建Xstram对象
                    sortXStream.autodetectAnnotations(true);
                    sortXStream.processAnnotations(AbsSortXml.class);
                    sortXStream.ignoreUnknownElements();
                }
                data = (AbsSortXml) sortXStream.fromXML(xml);
            }
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$");
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$");

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }


    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        return xml(result, xml, sourceKey, 0);
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey, int token) {
        try {
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data;
            synchronized (SourceViewModel.class) {
                if (absXStream == null) {
                    absXStream = new XStream(new DomDriver());//创建Xstram对象
                    absXStream.autodetectAnnotations(true);
                    absXStream.processAnnotations(AbsXml.class);
                    absXStream.ignoreUnknownElements();
                }
                data = (AbsXml) absXStream.fromXML(xml);
            }
            absXml(data, sourceKey);
            deliver(result, data, token);
            return data;
        } catch (Exception e) {
            deliver(result, null, token);
            return null;
        }
    }

    private void deliver(MutableLiveData<AbsXml> result, AbsXml data, int token) {
        if (searchResult == result) {
            postSearchEvent(RefreshEvent.TYPE_SEARCH_RESULT, data, token);
        } else if (quickSearchResult == result) {
            postSearchEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data, token);
        } else if (result != null) {
            if (result == detailResult && data != null) {
                checkThunder(data, 0);
            } else {
                result.postValue(data);
            }
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        return json(result, json, sourceKey, 0);
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey, int token) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = GSON.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            deliver(result, data, token);
            return data;
        } catch (Exception e) {
            deliver(result, null, token);
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }
}