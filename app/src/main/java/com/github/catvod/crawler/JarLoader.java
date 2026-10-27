package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor();

    private ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> jarLocks = new ConcurrentHashMap<>();
    private final Set<String> refreshedJars = ConcurrentHashMap.newKeySet();
    private volatile String recentJarKey = "";

    public static final class Prepared {
        private final DexClassLoader classLoader;
        private final Method init;
        private final Method proxy;

        private Prepared(DexClassLoader classLoader, Method init, Method proxy) {
            this.classLoader = classLoader;
            this.init = init;
            this.proxy = proxy;
        }
    }

    /**
     * 创建 ClassLoader 并解析 Init，耗时较长，不要在主线程调用
     */
    public Prepared prepare(String jar) {
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            // make force wait here, some device async dex load
            for (int count = 0; count < 5; count++) {
                try {
                    Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    Method init = classInit.getMethod("init", Context.class);
                    Method proxy = null;
                    try {
                        proxy = classLoader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map.class);
                    } catch (Throwable ignored) {
                    }
                    return new Prepared(classLoader, init, proxy);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    /**
     * 替换主 jar 并执行 Init.init，部分 jar 依赖主线程，需在主线程调用
     */
    public boolean activate(Prepared prepared) {
        spiders.clear();
        recentJarKey = "main";
        proxyMethods.clear();
        classLoaders.clear();
        return invokeInit("main", prepared);
    }

    public boolean load(String cache) {
        Prepared prepared = prepare(cache);
        return prepared != null && activate(prepared);
    }

    private boolean invokeInit(String key, Prepared prepared) {
        try {
            prepared.init.invoke(null, App.getInstance());
            System.out.println("自定义爬虫代码加载成功!");
            classLoaders.put(key, prepared.classLoader);
            if (prepared.proxy != null)
                proxyMethods.put(key, prepared.proxy);
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    private boolean loadClassLoader(String jar, String key) {
        Prepared prepared = prepare(jar);
        return prepared != null && invokeInit(key, prepared);
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        DexClassLoader loaded = classLoaders.get(key);
        if (loaded != null)
            return loaded;
        Object lock = jarLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            loaded = classLoaders.get(key);
            if (loaded != null)
                return loaded;
            File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + key + ".jar");
            boolean usable = cache.isFile() && cache.length() > 0
                    && (md5.isEmpty() || MD5.getFileMd5(cache).equalsIgnoreCase(md5));
            if (usable && loadClassLoader(cache.getAbsolutePath(), key)) {
                if (md5.isEmpty())
                    refreshInBackground(jar, cache);
                return classLoaders.get(key);
            }
            if (download(jar, cache)) {
                refreshedJars.add(jar);
                loadClassLoader(cache.getAbsolutePath(), key);
            }
            return classLoaders.get(key);
        }
    }

    private void refreshInBackground(String jar, File cache) {
        if (!refreshedJars.add(jar))
            return;
        REFRESH.execute(() -> download(jar, cache));
    }

    private static boolean download(String jar, File cache) {
        try (Response response = OkGo.<File>get(jar).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                return false;
            return FileUtils.writeAtomic(cache, response.body().byteStream());
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey = "";
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        if (spiders.containsKey(key))
            return spiders.get(key);
        DexClassLoader classLoader = null;
        if (jarKey.equals("main"))
            classLoader = classLoaders.get("main");
        else {
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
        }
        if (classLoader == null)
            return new SpiderNull();
        try {
            Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
            sp.init(App.getInstance(), ext);
//            if (!jar.isEmpty()) {
//                sp.homeContent(false); // 增加此行 应该可以解决部分写的有问题源的历史记录问题 但会增加这个源的首次加载时间 不需要可以已删掉
//            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {

        }
        return null;
    }
}
