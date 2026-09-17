package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 瓜子影视（bp7kprw 域名池）** 封闭签名 API 逆向实现 **
 * <p>
 * 一、通信协议（全部 POST，参数走表单体，服务端只认加密体）
 * request_key = HEX(AES/CBC/PKCS5(JSON(params), key, iv))   key/iv 每次随机 16 位
 * keys        = BASE64(RSA/ECB/PKCS1({key,iv}))
 * signature   = UPPER(MD5("token_id=,token=,phone_type=1,request_key=..,app_id=1,time=..,keys=.." + "*" + APP_KEY))
 * <p>
 * 二、必需的请求头
 * code = Walle 渠道号（APK Signing Block id=0x71777777 内 JSON 的 channel 字段），
 * 缺这个头所有业务接口一律 401「非官方渠道安装，无法访问」。
 * <p>
 * 三、响应
 * data.keys       → 用内置 RSA 私钥解出本次会话 {key,iv}
 * data.response_key → 用该 key/iv 做 AES 解密得到业务 JSON（HEX 密文）
 * <p>
 * 四、token
 * 匿名设备注册 /App/Authentication/Device/signUp（old_key/new_key/phone_type/code），
 * 成功即回 token，后续所有接口复用；过期自动重注册一次。
 * <p>
 * 五、路由
 * 分类树   /App/Resource/VodType/show
 * 筛选项   /App/IndexList/indexScreen   ?t_id=
 * 列表     /App/IndexList/indexList     tid/page/pageSize/sub/class/sort/lang/area/year
 * 首页      /App/IndexList/index        pid=
 * 搜索     /App/Index/findMoreVod       keywords/order_val/search_type
 * 详情     /App/Resource/Vod/showOne    d_id=        （只回播放线路，不带片名海报）
 * 剧集     /App/Resource/Vurl/show      vurl_cloud_id=&vod_d_id=
 * 播放     /App/Resource/VurlDetail/showOne  vod_id=&domain_type=&vurl_id=&resolution=&type=
 */
public class GuaZi extends Spider {

    private static final String DEFAULT_HOST = "https://api.bp7kprw.com";
    /** Walle 渠道号，取自 APK Signing Block，缺失会 401 非官方渠道 */
    private static final String CHANNEL = "GZ0001";
    private static final String APP_KEY = "&zvdvdvddbfikkkumtmdwqppp?|4Y!s!2br";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
    /**
     * 播放地址必须用「非浏览器」UA 请求，且不能带 Referer。
     * CDN 对浏览器 UA / 带 Referer 的请求返回 302 广告片（20 秒），
     * 只有 ExoPlayer/VLC/okhttp 这类 UA 才能拿到完整 m3u8。
     */
    private static final String PLAY_UA = "okhttp/4.9.0";
    private static final String PACKAGE = "com.i1bc8b7138.b20cb93d40.y776772c8820260917";
    private static final String VERSION = "2608011";
    private static final String API_VER = "3.0.5.2";
    private static final int PAGE_SIZE = 30;

    private static final String PUB_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDUM5+/y8sPsWkd1/RQS64X259E"
            + "UwxFXFE5HlA65MqrxnPs0JqoSRojSDy5QhwvROlaD6TwRQHKMY2OAZ6SnQeUJsCh"
            + "TEFIR9qUkwrs3/MVUMxjsv6JS6Oe/juclyJGTgVmDhB55EafXsD0SQYVj/QXXsxR"
            + "6ewR5E2kL52yAAD4yQIDAQAB";

    /** 内置 RSA 私钥池，逐个试解 data.keys */
    private static final String[] PRIV_KEYS = {
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGAe6hKrWLi1zQmjTT1"
                    + "ozbE4QdFeJGNxubxld6GrFGximxfMsMB6BpJhpcTouAqywAFppiKetUBBbXwYsYU"
                    + "1wNr648XVmPmCMCy4rY8vdliFnbMUj086DU6Z+/oXBdWU3/b1G0DN3E9wULRSwcK"
                    + "ZT3wj/cCI1vsCm3gj2R5SqkA9Y0CAwEAAQKBgAJH+4CxV0/zBVcLiBCHvSANm0l7"
                    + "HetybTh/j2p0Y1sTXro4ALwAaCTUeqdBjWiLSo9lNwDHFyq8zX90+gNxa7c5EqcW"
                    + "V9FmlVXr8VhfBzcZo1nXeNdXFT7tQ2yah/odtdcx+vRMSGJd1t/5k5bDd9wAvYdI"
                    + "DblMAg+wiKKZ5KcdAkEA1cCakEN4NexkF5tHPRrR6XOY/XHfkqXxEhMqmNbB9U34"
                    + "saTJnLWIHC8IXys6Qmzz30TtzCjuOqKRRy+FMM4TdwJBAJQZFPjsGC+RqcG5UvVM"
                    + "iMPhnwe/bXEehShK86yJK/g/UiKrO87h3aEu5gcJqBygTq3BBBoH2md3pr/W+hUM"
                    + "WBsCQQChfhTIrdDinKi6lRxrdBnn0Ohjg2cwuqK5zzU9p/N+S9x7Ck8wUI53DKm8"
                    + "jUJE8WAG7WLj/oCOWEh+ic6NIwTdAkEAj0X8nhx6AXsgCYRql1klbqtVmL8+95KZ"
                    + "K7PnLWG/IfjQUy3pPGoSaZ7fdquG8bq8oyf5+dzjE/oTXcByS+6XRQJAP/5ciy1b"
                    + "L3NhUhsaOVy55MHXnPjdcTX0FaLi+ybXZIfIQ2P4rb19mVq1feMbCXhz+L1rG8oa"
                    + "t5lYKfpe8k83ZA=="
    };

    private static final String CHARS = "zxcvbnmlkjhgfdsaqwertyuiopQWERTYUIOPASDFGHJKLZXCVBNM1234567890";
    private static final Random RANDOM = new Random();

    /** 列表条目缓存：detailContent 只有 d_id，片名海报需从列表回捞 */
    private static final LinkedHashMap<String, JSONObject> VIDEO_CACHE = new LinkedHashMap<>();
    private static final int CACHE_MAX = 600;
    /** 每个分类的筛选项只拉一次（多线程并发写入） */
    private static final Map<String, JSONArray> FILTER_CACHE = new ConcurrentHashMap<>();
    private static JSONArray CLASS_CACHE;
    /**
     * 首页要拉 1 次分类树 + 每个分类 1 次筛选项（共 12 次往返），串行约 5 秒。
     * 用线程池并发后约 0.6 秒。
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(12, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "guazi-http");
            t.setDaemon(true);   // 守护线程，避免拖住宿主 JVM 退出
            return t;
        }
    });

    private String host = DEFAULT_HOST;
    private String token = "";
    private String installCode = "";
    /** ext 传 {"direct":true} 时不做本机代理，直接下发 CDN 原地址（排障用） */
    private boolean directPlay = false;

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null && extend.trim().length() > 0) {
            String ext = extend.trim();
            try {
                String h;
                if (ext.startsWith("{")) {
                    JSONObject cfg = new JSONObject(ext);
                    h = cfg.optString("host", "");
                    directPlay = cfg.optBoolean("direct", false);
                } else h = ext;
                if (h.startsWith("http")) {
                    h = h.trim();
                    host = h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = CLASS_CACHE;
        if (classes == null) {
            classes = new JSONArray();
            try {
                JSONObject r = api("/App/Resource/VodType/show", new LinkedHashMap<String, String>());
                JSONObject plain = r.optJSONObject("plain");
                JSONArray arr = plain == null ? null : plain.optJSONArray("list");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        String name = it.optString("t_name", "");
                        String tid = it.optString("t_id", "");
                        if (name.length() == 0 || tid.length() == 0) continue;
                        classes.put(new JSONObject().put("type_id", tid).put("type_name", name));
                    }
                }
            } catch (Throwable ignored) {
            }
            if (classes.length() == 0) {
                classes.put(new JSONObject().put("type_id", "1").put("type_name", "电影"));
                classes.put(new JSONObject().put("type_id", "2").put("type_name", "连续剧"));
                classes.put(new JSONObject().put("type_id", "3").put("type_name", "综艺"));
                classes.put(new JSONObject().put("type_id", "4").put("type_name", "动漫"));
            }
            CLASS_CACHE = classes;
        }
        // 各分类的筛选项并发拉取；buildFilters 内部有缓存，二次进入不再发请求
        final JSONArray cls = classes;
        final JSONObject filters = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(cls.length());
        for (int i = 0; i < cls.length(); i++) {
            final String tid = cls.optJSONObject(i).optString("type_id");
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONArray f = buildFilters(tid);
                        synchronized (filters) {
                            filters.put(tid, f);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await(8, TimeUnit.SECONDS);
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        // 三个首页聚合位并发拉取
        final String[] pids = {"6", "1", "2"};
        final JSONArray[] parts = new JSONArray[pids.length];
        final CountDownLatch latch = new CountDownLatch(pids.length);
        for (int i = 0; i < pids.length; i++) {
            final int idx = i;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        parts[idx] = homeGroup(pids[idx]);
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await(8, TimeUnit.SECONDS);

        JSONArray list = new JSONArray();
        for (JSONArray part : parts) {
            if (part == null) continue;
            for (int i = 0; i < part.length() && list.length() < 40; i++) list.put(part.opt(i));
        }
        return new JSONObject().put("list", list).toString();
    }

    /** 取单个首页聚合位（pid）下的全部影片 */
    private JSONArray homeGroup(String pid) throws Exception {
        JSONArray out = new JSONArray();
        JSONObject r = api("/App/IndexList/index", single("pid", pid));
        JSONObject plain = r.optJSONObject("plain");
        JSONArray groups = plain == null ? null : plain.optJSONArray("list");
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            JSONArray items = g == null ? null : g.optJSONArray("list");
            if (items == null) continue;
            for (int j = 0; j < items.length(); j++) {
                JSONObject v = video(items.optJSONObject(j));
                if (v != null) out.put(v);
            }
        }
        return out;
    }

    /** 筛选项来自 indexScreen，失败则退回通用地区/年份/排序 */
    private JSONArray buildFilters(String tid) throws Exception {
        if (FILTER_CACHE.containsKey(tid)) return FILTER_CACHE.get(tid);
        JSONArray out = new JSONArray();
        try {
            JSONObject r = api("/App/IndexList/indexScreen", single("t_id", tid));
            JSONObject plain = r.optJSONObject("plain");
            if (plain != null) {
                out.put(group("sub", "类型", plain.optJSONArray("sub")));
                out.put(group("area", "地区", plain.optJSONArray("area")));
                out.put(group("year", "年份", plain.optJSONArray("year")));
                out.put(group("sort", "排序", plain.optJSONArray("sort")));
            }
        } catch (Throwable ignored) {
        }
        FILTER_CACHE.put(tid, out);
        return out;
    }

    /** indexScreen 里 name=显示名  value=提交值（sub 的 value 是数字） */
    private JSONObject group(String key, String name, JSONArray src) throws Exception {
        JSONArray values = new JSONArray();
        values.put(new JSONObject().put("n", "全部").put("v", ""));
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject it = src.optJSONObject(i);
                if (it == null) continue;
                String n = it.optString("name", "");
                String v = it.optString("value", "");
                if (n.length() == 0) continue;
                // 第一项一般是「地区/年份/综合」占位，value=0 表示不限
                if ("0".equals(v) || "地区".equals(n) || "年份".equals(n) || "综合".equals(n)) continue;
                values.put(new JSONObject().put("n", n).put("v", v));
            }
        }
        return new JSONObject().put("key", key).put("name", name).put("value", values);
    }

    // ==================== 分类 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Math.max(1, parseInt(pg, 1));
        HashMap<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("tid", tid);
        p.put("page", String.valueOf(page));
        p.put("pageSize", String.valueOf(PAGE_SIZE));
        putIf(p, "sub", ext.get("sub"));
        putIf(p, "class", ext.get("class"));
        putIf(p, "sort", ext.get("sort"));
        putIf(p, "lang", ext.get("lang"));
        putIf(p, "area", ext.get("area"));
        putIf(p, "year", ext.get("year"));

        JSONObject r = api("/App/IndexList/indexList", p);
        JSONObject plain = r.optJSONObject("plain");
        JSONArray list = new JSONArray();
        int pagecount = 1;
        if (plain != null) {
            JSONArray arr = plain.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = video(arr.optJSONObject(i));
                    if (v != null) list.put(v);
                }
            }
            int total = plain.optInt("total", 0);
            if (total > 0) pagecount = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        }
        return new JSONObject().put("list", list).put("page", page).put("pagecount", pagecount)
                .put("limit", PAGE_SIZE).put("total", pagecount * PAGE_SIZE).toString();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = Math.max(1, parseInt(pg, 1));
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("keywords", key);
        p.put("order_val", "");
        p.put("search_type", "1");
        p.put("page", String.valueOf(page));

        JSONObject r = api("/App/Index/findMoreVod", p);
        JSONObject plain = r.optJSONObject("plain");
        JSONArray list = new JSONArray();
        if (plain != null) {
            JSONArray arr = plain.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject v = video(arr.optJSONObject(i));
                    if (v != null) list.put(v);
                }
            }
        }
        int pagecount = list.length() >= 15 ? page + 1 : page;
        return new JSONObject().put("list", list).put("page", page).put("pagecount", pagecount)
                .put("limit", 20).put("total", pagecount * 20).toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0).trim();
        JSONObject cached = VIDEO_CACHE.get(id);

        JSONObject vod = cached == null ? new JSONObject() : video(cached);
        if (vod == null) vod = new JSONObject();
        if (vod.optString("vod_id").length() == 0) vod.put("vod_id", id);
        if (vod.optString("vod_name").length() == 0) vod.put("vod_name", id);
        if (vod.optString("vod_pic").length() == 0) vod.put("vod_pic", "");

        JSONArray from = new JSONArray();
        JSONArray urls = new JSONArray();
        try {
            JSONObject r = api("/App/Resource/Vod/showOne", single("d_id", id));
            JSONObject plain = r.optJSONObject("plain");
            JSONArray clouds = plain == null ? null : plain.optJSONArray("vurl_clouds");
            if (clouds != null) {
                for (int i = 0; i < clouds.length(); i++) {
                    JSONObject cloud = clouds.optJSONObject(i);
                    if (cloud == null) continue;
                    String cloudId = cloud.optString("id", "");
                    String cloudName = cloud.optString("name", "线路" + (i + 1));
                    if (cloudId.length() == 0) continue;

                    LinkedHashMap<String, String> q = new LinkedHashMap<>();
                    q.put("vurl_cloud_id", cloudId);
                    q.put("vod_d_id", id);
                    JSONObject rr = api("/App/Resource/Vurl/show", q);
                    JSONObject pl = rr.optJSONObject("plain");
                    JSONArray eps = pl == null ? null : pl.optJSONArray("list");
                    if (eps == null || eps.length() == 0) continue;

                    StringBuilder play = new StringBuilder();
                    for (int j = 0; j < eps.length(); j++) {
                        JSONObject ep = eps.optJSONObject(j);
                        if (ep == null) continue;
                        String title = ep.optString("title", "");
                        String param = bestParam(ep);
                        if (param.length() == 0) continue;
                        if (title.length() == 0) title = "第" + (j + 1) + "集";
                        if (play.length() > 0) play.append('#');
                        play.append(title.replace('$', ' ').replace('#', ' ')).append('$').append(param);
                    }
                    if (play.length() == 0) continue;
                    from.put(cloudName + (i + 1));
                    urls.put(play.toString());
                }
            }
        } catch (Throwable ignored) {
        }

        if (from.length() == 0) {
            from.put("瓜子");
            urls.put("");
        }
        vod.put("vod_play_from", join(from, "$$$"));
        vod.put("vod_play_url", join(urls, "$$$"));
        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    /**
     * 优先「正片」play 的最高可用清晰度，其次 board(花絮)，最后才用 default_param。
     * 服务端给的 default_param 有时是 type=board，能拿到地址但语义不对。
     * show_type=2 表示该清晰度不可用（param 为空）。
     */
    private String bestParam(JSONObject ep) {
        for (String key : new String[]{"play", "board"}) {
            JSONObject group = ep.optJSONObject(key);
            if (group == null) continue;
            for (String res : new String[]{"1080", "720", "480"}) {
                JSONObject one = group.optJSONObject(res);
                if (one == null) continue;
                if ("2".equals(one.optString("show_type", ""))) continue;
                String param = one.optString("param", "");
                if (param.length() > 0) return param;
            }
        }
        return ep.optString("default_param", "");
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Map<String, String> m = parseQuery(id);
        String vodId = value(m, "vod_d_id", value(m, "vod_id", ""));
        String vurlId = value(m, "vurl_id", "");
        if (vodId.length() == 0 || vurlId.length() == 0) {
            return new JSONObject().put("parse", 1).put("playUrl", "").put("url", "").toString();
        }
        String domainType = value(m, "domain_type", "8");
        String resolution = value(m, "resolution", "1080");
        String type = value(m, "type", "play");

        // 逐个清晰度/类型组合试探，拿到第一个非空地址
        String playUrl = "";
        String[] types = {type, "play", "board", "screen", "download"};
        String[] resolutions = {resolution, "1080", "720", "480"};
        for (String res : resolutions) {
            for (String tp : types) {
                LinkedHashMap<String, String> p = new LinkedHashMap<>();
                p.put("vod_id", vodId);
                p.put("vurl_id", vurlId);
                p.put("domain_type", domainType);
                p.put("resolution", res);
                p.put("type", tp);
                try {
                    JSONObject r = api("/App/Resource/VurlDetail/showOne", p);
                    JSONObject plain = r.optJSONObject("plain");
                    String url = plain == null ? "" : plain.optString("url", "");
                    if (url.length() > 0) {
                        playUrl = url;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (playUrl.length() > 0) break;
        }
        // 该 CDN 做了「广告注入式防盗链」：请求带 Referer 或 UA 含 Mozilla 时，
        // m3u8 与 ts 都会被 302 到广告站 app.wanglaoshi.中国（占位片，用户看到的就是「视频丢失」）。
        // header 仍然照常下发，但实测播放器内核（尤其 WebView/X5 系）常无视它，届时 UA 仍是浏览器串，
        // 于是这里默认改走本机回环代理：由爬虫按 CDN 要求取流，播放器只跟 127.0.0.1 通信。
        JSONObject header = new JSONObject();
        header.put("User-Agent", PLAY_UA);
        String play = directPlay ? playUrl : Relay.register(playUrl);
        return new JSONObject()
                .put("parse", 0)
                .put("jx", 0)
                .put("playUrl", play)
                .put("url", play)
                .put("header", header.toString())
                .toString();
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return url != null && (url.contains(".m3u8") || url.contains(".mp4"));
    }

    // ==================== 通信 ====================

    private JSONObject api(String path, LinkedHashMap<String, String> params) throws Exception {
        ensureToken(path);
        JSONObject r = request(path, params);
        if (isTokenExpired(r)) {
            token = "";
            ensureToken(path);
            r = request(path, params);
        }
        return r;
    }

    /** 并发拉首页时只允许注册一次设备，其余线程复用 token */
    private synchronized void ensureToken(String path) throws Exception {
        if (token.length() > 0) return;
        if (path.contains("/Authentication/Device/signUp")) return;
        if (installCode.length() == 0) installCode = rand16();
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("old_key", rand16());
        p.put("new_key", rand16());
        p.put("phone_type", "1");
        p.put("code", installCode);
        JSONObject r = request("/App/Authentication/Device/signUp", p);
        JSONObject plain = r.optJSONObject("plain");
        String t = plain == null ? "" : plain.optString("token", "");
        if (t.length() > 0) token = t;
    }

    private boolean isTokenExpired(JSONObject r) {
        if (r == null) return false;
        String msg = r.optString("msg", "");
        String code = r.optString("code", "");
        return ("301".equals(code) || "402".equals(code)) && (msg.contains("token") || msg.contains("登录"));
    }

    private JSONObject request(String path, LinkedHashMap<String, String> params) {
        try {
            String key = rand16();
            String iv = rand16();
            String json = params == null || params.isEmpty()
                    ? "{}" : new JSONObject(params).toString();
            String requestKey = aesEncrypt(json, key, iv);
            String keys = rsaEncrypt("{\"key\":\"" + key + "\",\"iv\":\"" + iv + "\"}");
            long time = System.currentTimeMillis() / 1000;

            StringBuilder sign = new StringBuilder();
            sign.append("token_id=,token=").append(token)
                    .append(",phone_type=1,request_key=").append(requestKey)
                    .append(",app_id=1,time=").append(time)
                    .append(",keys=").append(keys);
            String signature = md5Upper(sign.toString() + "*" + APP_KEY);

            StringBuilder form = new StringBuilder();
            form.append("token=").append(enc(token));
            form.append("&token_id=");
            form.append("&phone_type=1");
            form.append("&time=").append(time);
            form.append("&phone_model=samsung-sm-s9080");
            form.append("&keys=").append(enc(keys));
            form.append("&request_key=").append(enc(requestKey));
            form.append("&signature=").append(signature);
            form.append("&app_id=1");
            form.append("&ad_version=1");

            String text = post(host + path, form.toString());

            JSONObject out = new JSONObject();
            JSONObject root = new JSONObject(text);
            out.put("code", root.optString("code", ""));
            out.put("msg", root.optString("msg", ""));
            JSONObject data = root.optJSONObject("data");
            if (data != null) {
                String plain = "";
                String responseKey = data.optString("response_key", "");
                if (responseKey.length() > 0) {
                    String sessionKeys = data.optString("keys", "");
                    String[] sk = sessionKeys.length() == 0 ? null : decryptSessionKeys(sessionKeys);
                    if (sk != null) plain = aesDecrypt(responseKey, sk[0], sk[1]);
                }
                if (plain.length() > 0) {
                    String trimmed = plain.trim();
                    if (trimmed.startsWith("{")) out.put("plain", new JSONObject(trimmed));
                    else if (trimmed.startsWith("[")) out.put("plain", new JSONArray(trimmed));
                }
            }
            return out;
        } catch (Throwable e) {
            JSONObject out = new JSONObject();
            try {
                out.put("code", "ERR").put("msg", String.valueOf(e.getMessage()));
            } catch (Throwable ignored) {
            }
            return out;
        }
    }

    private String[] decryptSessionKeys(String base64) {
        byte[] data = Base64.decode(base64, Base64.DEFAULT);
        for (String pk : PRIV_KEYS) {
            try {
                PrivateKey key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pk, Base64.DEFAULT)));
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, key);
                JSONObject o = new JSONObject(new String(cipher.doFinal(data), "UTF-8"));
                return new String[]{o.optString("key", ""), o.optString("iv", "")};
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String post(String url, String form) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Version", VERSION);
            conn.setRequestProperty("Ver", API_VER);
            conn.setRequestProperty("api-ver", API_VER);
            conn.setRequestProperty("PackageName", PACKAGE);
            conn.setRequestProperty("Referer", host + "/");
            conn.setRequestProperty("lang", "zh_cn");
            // 渠道校验头，缺失即 401 非官方渠道安装
            conn.setRequestProperty("code", CHANNEL);
            if (token.length() > 0) conn.setRequestProperty("token", token);

            byte[] bs = form.getBytes("UTF-8");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(bs.length);
            OutputStream os = conn.getOutputStream();
            os.write(bs);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== 工具 ====================

    private static String aesEncrypt(String plain, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(fix16(key).getBytes("UTF-8"), "AES"),
                new IvParameterSpec(fix16(iv).getBytes("UTF-8")));
        return hex(cipher.doFinal(plain.getBytes("UTF-8")));
    }

    private static String aesDecrypt(String hexText, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(fix16(key).getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(fix16(iv).getBytes("UTF-8")));
            return new String(cipher.doFinal(unhex(hexText)), "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String rsaEncrypt(String plain) throws Exception {
        PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.decode(PUB_KEY, Base64.DEFAULT)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private static String md5Upper(String text) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(text.getBytes("UTF-8")));
    }

    /** key/iv 不足 16 位补 '0'，超出截断 */
    private static String fix16(String s) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < 16) sb.append('0');
        return sb.substring(0, 16);
    }

    private static String rand16() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return sb.toString();
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            String h = Integer.toHexString(b & 0xFF).toUpperCase();
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private JSONObject video(JSONObject it) {
        if (it == null) return null;
        String id = it.optString("vod_id", "");
        String name = it.optString("vod_name", "").trim();
        if (id.length() == 0 || name.length() == 0) return null;
        if (VIDEO_CACHE.size() >= CACHE_MAX) {
            Iterator<String> it2 = VIDEO_CACHE.keySet().iterator();
            for (int n = 0; n < CACHE_MAX / 4 && it2.hasNext(); n++) {
                it2.next();
                it2.remove();
            }
        }
        VIDEO_CACHE.put(id, it);
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", id);
            v.put("vod_name", name);
            v.put("vod_pic", it.optString("vod_pic", ""));
            String remarks = it.optString("new_continue", "");
            if (remarks.length() == 0) remarks = it.optString("vod_remarks", "");
            if (remarks.length() == 0 && it.optBoolean("is_end", false)) remarks = "已完结";
            if (remarks.length() == 0) {
                int cont = parseInt(it.optString("vod_continu", "0"), 0);
                if (cont > 0) remarks = "更新至" + cont + "集";
            }
            v.put("vod_remarks", remarks);
            v.put("vod_year", it.optString("vod_year", ""));
            v.put("vod_area", it.optString("vod_area", ""));
            v.put("vod_actor", it.optString("vod_actor", ""));
            String director = it.optString("vod_director", "");
            if (director.length() == 0) director = it.optString("vod_directed", "");
            v.put("vod_director", director);
            v.put("vod_score", it.optString("vod_scroe", ""));
            String content = it.optString("d_class", "");
            if (content.length() == 0) content = it.optString("vod_class", "");
            v.put("vod_content", content);
        } catch (Throwable ignored) {
        }
        return v;
    }

    private static String join(JSONArray arr, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    private static void putIf(LinkedHashMap<String, String> map, String key, String value) {
        if (value == null) return;
        value = value.trim();
        if (value.length() == 0 || "0".equals(value)) return;
        map.put(key, value);
    }

    private static LinkedHashMap<String, String> single(String key, String value) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static String value(Map<String, String> map, String key, String def) {
        String v = map.get(key);
        return v == null || v.length() == 0 ? def : v;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            map.put(pair.substring(0, i), pair.substring(i + 1));
        }
        return map;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    // ==================== 本机回环中继（绕开「按请求头」判定的防盗链） ====================

    /**
     * 这条 CDN 不看签名、只看请求头：<b>UA 含 Mozilla（WebView / X5 / 浏览器内核播放器）</b>
     * 或 <b>带任意 Referer</b>（连 Origin 也不行）时，m3u8 与 ts 一律 302 到广告站，客户端
     * 「播」出来的就是那支占位片（用户看到的就是「源视频文件丢失 / 视频丢失」）。
     * 实测 ExoPlayerLib / Lavf(ijk) / stagefright / Dalvik / 空 UA 都能拿到正片（775 段），
     * 说明拦截点是「浏览器特征」而不是播放器本身 —— 但播放器用哪个 UA、加不加 Referer，
     * 爬虫完全左右不了（header 字段常被内核忽略）。
     * <p>
     * 于是这里由爬虫自己按 CDN 要求（非浏览器 UA + 不带 Referer）取流，再在 127.0.0.1 上
     * 开一个临时端口把流喂给播放器：播放列表里的分片、密钥地址全部改写成代理地址，
     * 播放器全程只跟本机通信，用什么 UA 都无所谓。
     */
    private static final class Relay {

        /** 目录 -> sid */
        private static final Map<String, String> DIR_SID = new ConcurrentHashMap<>();
        /** sid -> 目录 */
        private static final Map<String, String> SID_DIR = new ConcurrentHashMap<>();
        private static final AtomicInteger SEQ = new AtomicInteger();
        private static volatile String host;
        private static ExecutorService workers;

        /** 启动中继（幂等），返回 http://127.0.0.1:端口；失败返回 null */
        static synchronized String host() {
            if (host != null) return host;
            try {
                final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                workers = Executors.newFixedThreadPool(8, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "guazi-relay");
                        t.setDaemon(true);
                        return t;
                    }
                });
                Thread accept = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            try {
                                final Socket socket = server.accept();
                                workers.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        serve(socket);
                                    }
                                });
                            } catch (Throwable e) {
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }
                }, "guazi-relay-accept");
                accept.setDaemon(true);
                accept.start();
                host = "http://127.0.0.1:" + server.getLocalPort();
            } catch (Throwable e) {
                host = null;   // 端口被占/无权限：退回直链，不影响其它逻辑
            }
            return host;
        }

        /** 登记会话，返回播放器可直接播放的本地地址；任何异常都退回原地址 */
        static String register(String url) {
            try {
                String base = host();
                if (base == null || url == null || url.length() == 0) return url;
                String dir = dir(url);
                String tail = url.substring(dir.length());
                if (tail.length() == 0) return url;
                return base + "/p/" + sid(dir) + "/" + tail;
            } catch (Throwable e) {
                return url;
            }
        }

        private static String sid(String dir) {
            String sid = DIR_SID.get(dir);
            if (sid != null) return sid;
            sid = Integer.toHexString(SEQ.incrementAndGet());
            if (SID_DIR.size() > 256) {          // 会话不会无限增长，超出后整体重建
                DIR_SID.clear();
                SID_DIR.clear();
            }
            DIR_SID.put(dir, sid);
            SID_DIR.put(sid, dir);
            return sid;
        }

        /** 取目录（含结尾 /），自动丢掉 query */
        private static String dir(String url) {
            int q = url.indexOf('?');
            String u = q >= 0 ? url.substring(0, q) : url;
            int s = u.indexOf("://");
            int hostEnd = s < 0 ? -1 : u.indexOf('/', s + 3);
            if (hostEnd < 0) return u + "/";
            int i = u.lastIndexOf('/');
            return i < hostEnd ? u.substring(0, hostEnd + 1) : u.substring(0, i + 1);
        }

        /** 相对地址解析成绝对地址 */
        private static String resolve(String dir, String ref) {
            if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
            if (ref.startsWith("//")) return "https:" + ref;
            if (ref.startsWith("/")) {
                int s = dir.indexOf("://");
                int hostEnd = s < 0 ? -1 : dir.indexOf('/', s + 3);
                return hostEnd < 0 ? dir + ref.substring(1) : dir.substring(0, hostEnd) + ref;
            }
            return dir + ref;
        }

        /** 内网地址改写：同目录用当前 sid（省掉一长串 URL），跨目录新开会话 */
        private static String proxy(String url, String curDir, String curSid, String host) {
            if (host == null || url == null || url.length() == 0) return url;
            if (url.startsWith(curDir)) return host + "/p/" + curSid + "/" + url.substring(curDir.length());
            String dir = dir(url);
            return host + "/p/" + sid(dir) + "/" + url.substring(dir.length());
        }

        /** 改写播放列表：分片行 + EXT-X-KEY/MAP 的 URI 属性 */
        private static String rewrite(String text, String url) {
            String host = host();
            String base = dir(url);
            String sid = sid(base);
            StringBuilder sb = new StringBuilder(text.length() + 8192);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                String raw = lines[i];
                String t = raw.trim();
                if (t.length() == 0) {
                    sb.append(raw);
                } else if (t.charAt(0) == '#') {
                    sb.append(rewriteAttr(raw, base, sid, host));
                } else {
                    sb.append(proxy(resolve(base, t), base, sid, host));
                }
            }
            return sb.toString();
        }

        private static String rewriteAttr(String raw, String curDir, String curSid, String host) {
            int i = raw.indexOf("URI=\"");
            if (i < 0) return raw;
            int j = raw.indexOf('"', i + 5);
            if (j < 0) return raw;
            String v = raw.substring(i + 5, j);
            return raw.substring(0, i + 5) + proxy(resolve(curDir, v), curDir, curSid, host) + raw.substring(j);
        }

        /** 一个连接 = 一次取流（播放器对每个分片/播放列表各开一条） */
        private static void serve(Socket socket) {
            HttpURLConnection conn = null;
            try {
                socket.setSoTimeout(20000);
                InputStream in = socket.getInputStream();
                String request = line(in);
                if (request == null || request.length() == 0) return;
                int sp = request.indexOf(' ');
                String method = sp > 0 ? request.substring(0, sp) : "GET";
                int sp2 = sp > 0 ? request.indexOf(' ', sp + 1) : -1;
                String path = sp2 > sp ? request.substring(sp + 1, sp2) : "";
                String range = null;
                for (String h = line(in); h != null && h.length() > 0; h = line(in)) {
                    int c = h.indexOf(':');
                    if (c > 0 && "range".equalsIgnoreCase(h.substring(0, c).trim())) range = h.substring(c + 1).trim();
                }
                OutputStream out = socket.getOutputStream();
                boolean head = "HEAD".equalsIgnoreCase(method);

                String target = null;
                if (path.startsWith("http://") || path.startsWith("https://")) {   // 绝对 URI 形式
                    int s = path.indexOf("://");
                    int slash = path.indexOf('/', s + 3);
                    path = slash < 0 ? "/" : path.substring(slash);
                }
                if (path.startsWith("/p/")) {
                    int k = path.indexOf('/', 3);
                    if (k > 3) {
                        String dir = SID_DIR.get(path.substring(3, k));
                        String tail = path.substring(k + 1);
                        if (dir != null && tail.length() > 0 && tail.indexOf("..") < 0) target = dir + tail;
                    }
                }
                if (target == null) {
                    byte[] b = "not found".getBytes("UTF-8");
                    send(out, 404, "text/plain; charset=utf-8", b.length, null, false);
                    if (!head) out.write(b);
                    out.flush();
                    return;
                }

                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", PLAY_UA);   // 关键：非浏览器 UA
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                // 关键：绝不带 Referer
                String plain = target;
                int qm = plain.indexOf('?');
                if (qm >= 0) plain = plain.substring(0, qm);
                boolean byName = plain.endsWith(".m3u8");
                // 播放列表不能被 Range 截断，否则改写出来的是半截列表
                if (range != null && !byName) conn.setRequestProperty("Range", range);

                int code = conn.getResponseCode();
                if (code >= 400) {
                    byte[] b = ("upstream " + code).getBytes("UTF-8");
                    send(out, code, "text/plain; charset=utf-8", b.length, null, false);
                    if (!head) out.write(b);
                    out.flush();
                    return;
                }
                String ctype = conn.getContentType();
                InputStream body = conn.getInputStream();
                byte[] first = new byte[2048];
                int n = fill(body, first);
                String name = plain.substring(plain.lastIndexOf('/') + 1);
                boolean playlist = byName
                        || (ctype != null && ctype.toLowerCase().contains("mpegurl"))
                        || isPlaylist(first, n);

                if (playlist) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(n + 4096);
                    bos.write(first, 0, n);
                    byte[] buf = new byte[16384];
                    int m;
                    while ((m = body.read(buf)) > 0) bos.write(buf, 0, m);
                    byte[] fixed = rewrite(new String(bos.toByteArray(), "UTF-8"), target).getBytes("UTF-8");
                    send(out, 200, "application/vnd.apple.mpegurl", fixed.length, null, true);
                    if (!head) out.write(fixed);
                } else {
                    String ct = name.endsWith(".ts") ? "video/mp2t"
                            : (ctype == null || ctype.length() == 0 ? "application/octet-stream" : ctype);
                    send(out, code == 206 ? 206 : 200, ct, size(conn.getHeaderField("Content-Length")),
                            conn.getHeaderField("Content-Range"), true);
                    if (!head) {
                        out.write(first, 0, n);
                        byte[] buf = new byte[32768];
                        int m;
                        while ((m = body.read(buf)) > 0) out.write(buf, 0, m);
                    }
                }
                out.flush();
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }

        private static void send(OutputStream out, int status, String ctype, int len, String contentRange,
                                 boolean noStore) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ')
                    .append(status == 200 ? "OK" : (status == 206 ? "Partial Content" : "ERR")).append("\r\n");
            sb.append("Content-Type: ").append(ctype).append("\r\n");
            if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
            if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            if (noStore) sb.append("Cache-Control: no-cache\r\n");
            sb.append("Connection: close\r\n\r\n");
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } catch (Throwable ignored) {
            }
        }

        /** 读一行（请求行/请求头，逐字节读到 \n） */
        private static String line(InputStream in) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
                int b;
                while ((b = in.read()) >= 0) {
                    if (b == '\n') break;
                    if (b != '\r') bos.write(b);
                }
                if (b < 0 && bos.size() == 0) return null;
                return new String(bos.toByteArray(), "UTF-8");
            } catch (Throwable e) {
                return null;
            }
        }

        /** 尽量填满缓冲区（不足则说明流结束了） */
        private static int fill(InputStream in, byte[] buf) {
            int off = 0;
            try {
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            } catch (Throwable ignored) {
            }
            return off;
        }

        private static boolean isPlaylist(byte[] head, int n) {
            int i = (n >= 3 && (head[0] & 0xFF) == 0xEF) ? 3 : 0;
            return n - i >= 7 && new String(head, i, 7).equals("#EXTM3U");
        }

        private static int size(String s) {
            if (s == null) return -1;
            try {
                return Integer.parseInt(s.trim());
            } catch (Throwable e) {
                return -1;
            }
        }
    }
}
