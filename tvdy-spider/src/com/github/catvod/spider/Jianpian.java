package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 荐片（官方 App 接口，加密协议版）
 * <p>
 * 来源：反编译官方 APK（腾讯 Shadow 插件壳 + WebView 业务包）分析所得。
 * <pre>
 * 域名发现: https://ssopj-1462720388.cos.accelerate.myqcloud.com/config.txt → cqjdn.com
 *           注意：cqjdn.com 泛域名当前为"剥离 query"的降级镜像(签名按纯路径校验)，
 *           备用域 api.bdgnbrws.com / api.fvevfbr.com / api.swgsdfew.com 为真实 API(签名含 query)。
 *
 * 加密协议（与 com.jp.runtime.web.RuntimeEncryptedApiClient 一致）:
 *   identity  : device_id = UUID, credential = base64url(32 随机字节)
 *   seed      = HMAC-SHA256( SHA256("jp-api-v1\0" + device_id), credential )
 *   kAuth     = HMAC(seed, "jp-api-request-auth-v1\x01")
 *   kReqBody  = HMAC(seed, "jp-api-request-body-v1\x01")
 *   kRespBody = HMAC(seed, "jp-api-response-body-v1\x01")
 *   签名      = base64url( HMAC(kAuth, "METHOD\n{path含query}\n{ts}\n{nonce}\n{sha256hex(body)}") )
 *   头        : X-JP-Crypto-Version:1, X-JP-Timestamp, X-JP-Nonce, X-JP-Signature,
 *               X-JP-Runtime-Authorization: Bearer {credential}, X-Device-ID
 *   POST body : A256GCM 信封 {"v":1,"alg":"A256GCM","ts":..,"data":..} AAD=jp-api-request-v1\n...
 *   响应      : A256GCM 信封 AAD=jp-api-response-v1\n...
 *
 * 接口:
 *   GET  /api/v1/catalog/categories                          分类+筛选 schema
 *   GET  /api/v1/home                                        首页
 *   GET  /api/v1/catalog/works?category_key=&area=&year=&class=&tag=&language=&cursor=
 *   GET  /api/v1/search?q=&page=&page_size=
 *   GET  /api/v1/works/{id}                                  详情
 *   GET  /api/v1/works/{id}/media-lines?delivery_type=playback
 *   GET  /api/v1/works/{id}/episodes?page_size=&line_key=&cursor=
 *   POST /api/v1/media/access {work_id, delivery_type:"playback", episode_key, episode_index}
 * </pre>
 */
public class Jianpian extends Spider {

    private static final String[] DEFAULT_HOSTS = {
            "https://api.bdgnbrws.com", "https://api.fvevfbr.com", "https://api.swgsdfew.com"};

    private static final String KIND_MOVIE = "movie", KIND_SERIES = "series", KIND_ANIME = "anime",
            KIND_VARIETY = "variety", KIND_DOC = "documentary", KIND_SHORT = "short_drama", KIND_SPORTS = "sports";

    private final SecureRandom random = new SecureRandom();

    // ===== 加密身份与密钥 =====
    private String deviceId;
    private String credential;
    private byte[] kAuth;
    private byte[] kReqBody;
    private byte[] kRespBody;

    // ===== 域名轮转 =====
    private String[] hosts = DEFAULT_HOSTS;
    private int hostIdx = 0;

    // ===== 分类筛选缓存 =====
    private JSONObject cachedCategories;

    // ===== 列表 cursor 分页缓存: key -> [页码, 游标] =====
    private final Map<String, String[]> cursorCache = new LinkedHashMap<String, String[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > 32;
        }
    };

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        try {
            if (extend != null) {
                String e = extend.trim();
                if (e.startsWith("{")) {
                    String host = new JSONObject(e).optString("host", "");
                    if (host.length() > 0) applyHost(host);
                } else if (e.length() > 0 && (e.startsWith("http") || e.matches("(?i)[a-z0-9.-]+\\.[a-z]{2,}"))) {
                    applyHost(e);
                }
            }
        } catch (Throwable ignored) {
        }
        rotateIdentity();
    }

    private void applyHost(String host) {
        if (!host.startsWith("http")) host = "https://" + host;
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        hosts = new String[]{host};
    }

    private void rotateIdentity() {
        deviceId = UUID.randomUUID().toString();
        byte[] cred = new byte[32];
        random.nextBytes(cred);
        credential = b64u(cred);
        try {
            byte[] master = sha256(("jp-api-v1\u0000" + deviceId).getBytes("UTF-8"));
            byte[] seed = hmac(master, credential.getBytes("UTF-8"));
            kAuth = hmac(seed, "jp-api-request-auth-v1\u0001".getBytes("UTF-8"));
            kReqBody = hmac(seed, "jp-api-request-body-v1\u0001".getBytes("UTF-8"));
            kRespBody = hmac(seed, "jp-api-response-body-v1\u0001".getBytes("UTF-8"));
        } catch (Throwable t) {
            // 不可能：UTF-8 必可用
        }
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        JSONObject filters = new JSONObject();
        try {
            JSONObject cats = apiGet("/api/v1/catalog/categories");
            cachedCategories = cats;
            JSONArray items = cats.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject c = items.getJSONObject(i);
                    classes.put(new JSONObject()
                            .put("type_id", c.optString("category_key"))
                            .put("type_name", c.optString("name")));
                    if (filter) buildFilters(filters, c);
                }
            }
        } catch (Throwable t) {
            cachedCategories = null;
            String[][] defs = {{"movie", "电影"}, {"series", "电视剧"}, {"anime", "动漫"},
                    {"variety", "综艺"}, {"documentary", "纪录片"}, {"short_drama", "短剧"}};
            for (String[] d : defs) classes.put(new JSONObject().put("type_id", d[0]).put("type_name", d[1]));
        }
        JSONObject result = new JSONObject().put("class", classes);
        if (filter && filters.length() > 0) result.put("filters", filters);
        return result.toString();
    }

    private void buildFilters(JSONObject filters, JSONObject category) throws Exception {
        String catKey = category.optString("category_key");
        JSONObject schema = category.optJSONObject("filter_schema");
        if (schema == null) return;
        JSONArray groups = schema.optJSONArray("groups");
        if (groups == null) return;
        JSONArray f = new JSONArray();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            String key = g.optString("key");
            if (!"area".equals(key) && !"year".equals(key) && !"class".equals(key)) continue;
            JSONArray opts = g.optJSONArray("options");
            if (opts == null || opts.length() == 0) continue;
            JSONArray vals = new JSONArray();
            vals.put(new JSONObject().put("n", "全部").put("v", ""));
            for (int j = 0; j < opts.length(); j++) {
                JSONObject o = opts.getJSONObject(j);
                vals.put(new JSONObject().put("n", o.optString("label")).put("v", o.optString("value")));
            }
            f.put(new JSONObject().put("key", key).put("name", g.optString("label")).put("value", vals));
        }
        if (f.length() > 0) filters.put(catKey, f);
    }

    @Override
    public String homeVideoContent() throws Exception {
        JSONArray list = new JSONArray();
        try {
            JSONObject home = apiGet("/api/v1/home");
            JSONArray latest = home.optJSONArray("latest");
            if (latest != null) {
                for (int i = 0; i < latest.length() && list.length() < 30; i++) {
                    JSONObject w = latest.getJSONObject(i);
                    if (!w.has("id")) continue;
                    list.put(workToVod(w));
                }
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject().put("list", list).toString();
    }

    private JSONObject workToVod(JSONObject w) throws Exception {
        String remarks = w.optString("remarks");
        if (remarks.length() == 0) {
            double score = w.optDouble("score", 0);
            remarks = score > 0 ? String.valueOf(score) : "";
        }
        return new JSONObject()
                .put("vod_id", String.valueOf(w.optLong("id", w.optInt("id"))))
                .put("vod_name", w.optString("title"))
                .put("vod_pic", w.optString("poster_url"))
                .put("vod_remarks", remarks);
    }

    // ==================== 分类 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        List<String> query = new ArrayList<>();
        query.add("category_key=" + urlEncode(tid));
        if (extend != null) {
            addFilter(query, extend, "area");
            addFilter(query, extend, "year");
            addFilter(query, extend, "class");
            addFilter(query, extend, "tag");
            addFilter(query, extend, "language");
        }
        String cacheKey = tid + "|" + (extend == null ? "" : extend.toString());

        String cursor = resolveCursor(cacheKey, page, query);
        List<String> q2 = new ArrayList<>(query);
        if (cursor != null && cursor.length() > 0) q2.add("cursor=" + urlEncode(cursor));
        q2.add("page_size=20");

        JSONObject resp;
        try {
            resp = apiGet("/api/v1/catalog/works?" + join(q2, "&"));
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).put("page", page).toString();
        }
        JSONArray items = resp.optJSONArray("items");
        String next = resp.optString("next_cursor", null);
        JSONArray list = new JSONArray();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) list.put(workToVod(items.getJSONObject(i)));
        }
        boolean hasMore = next != null && next.length() > 0 && items != null && items.length() > 0;
        cursorCache.put(cacheKey, new String[]{String.valueOf(page), hasMore ? next : null});
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", hasMore ? page + 1 : page)
                .put("limit", "20")
                .put("total", hasMore ? 99999 : 20 * page)
                .toString();
    }

    private void addFilter(List<String> query, HashMap<String, String> extend, String key) {
        String v = extend.get(key);
        if (v != null && v.length() > 0) query.add(key + "=" + urlEncode(v));
    }

    /**
     * 列表为 cursor 分页，Box 需要 page 分页：
     * 维护每页末尾游标；请求页 == 缓存页+1 直接续读；越页时从缓存处顺序补齐。
     */
    private String resolveCursor(String cacheKey, int page, List<String> query) {
        String[] state = cursorCache.get(cacheKey);
        if (page <= 1) return null;
        int fromPage = 1;
        String cursor = null;
        if (state != null) {
            int cachedPage = parseInt(state[0], 1);
            String cachedCursor = state[1];
            if (page == cachedPage + 1 && cachedCursor != null) return cachedCursor;
            if (cachedCursor != null && page > cachedPage) {
                fromPage = cachedPage;
                cursor = cachedCursor;
            }
        }
        // 顺序补齐到 page-1
        for (int p = fromPage; p < page; p++) {
            List<String> q2 = new ArrayList<>(query);
            if (cursor != null && cursor.length() > 0) q2.add("cursor=" + urlEncode(cursor));
            q2.add("page_size=20");
            try {
                JSONObject resp = apiGet("/api/v1/catalog/works?" + join(q2, "&"));
                String next = resp.optString("next_cursor", null);
                if (next == null || next.length() == 0) return "";
                cursor = next;
            } catch (Throwable t) {
                return cursor;
            }
        }
        return cursor;
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        try {
            JSONObject d = apiGet("/api/v1/works/" + id);
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", d.optString("title"));
            vod.put("vod_pic", d.optString("poster_url"));
            vod.put("vod_year", d.opt("year") == null ? "" : String.valueOf(d.opt("year")));
            vod.put("vod_area", d.optString("area"));
            vod.put("vod_remarks", d.optString("remarks"));
            vod.put("type_name", kindName(d.optString("kind")));
            StringBuilder actor = new StringBuilder();
            StringBuilder director = new StringBuilder();
            JSONArray credits = d.optJSONArray("credits");
            if (credits != null) {
                for (int i = 0; i < credits.length(); i++) {
                    JSONObject c = credits.getJSONObject(i);
                    String name = c.optString("name");
                    String role = c.optString("role", c.optString("kind"));
                    if ("director".equals(role) && director.length() == 0) director.append(name);
                    else if (actor.length() < 200) {
                        if (actor.length() > 0) actor.append(" ");
                        actor.append(name);
                    }
                }
            }
            vod.put("vod_actor", actor.toString());
            vod.put("vod_director", director.toString());
            String summary = d.optString("summary");
            if (summary.length() == 0) summary = d.optString("synopsis");
            vod.put("vod_content", summary);

            // 播放线路 + 剧集
            List<String> froms = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            JSONArray lines = null;
            try {
                JSONObject ml = apiGet("/api/v1/works/" + id + "/media-lines?delivery_type=playback");
                lines = ml.optJSONArray("items");
            } catch (Throwable ignored) {
            }
            if (lines != null && lines.length() > 0) {
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.getJSONObject(i);
                    String lineKey = line.optString("key");
                    int epCount = line.optInt("episode_count", 0);
                    if (epCount <= 0) continue;
                    String eps = fetchEpisodes(id, lineKey);
                    if (eps.length() > 0) {
                        froms.add(line.optString("name", lineKey));
                        urls.add(eps);
                    }
                }
            }
            if (froms.isEmpty()) {
                String eps = fetchEpisodes(id, null);
                if (eps.length() > 0) {
                    froms.add("荐片");
                    urls.add(eps);
                }
            }
            vod.put("vod_play_from", join(froms, "$$$"));
            vod.put("vod_play_url", join(urls, "$$$"));

            JSONArray list = new JSONArray();
            list.put(vod);
            return new JSONObject().put("list", list).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    /** 拉取全部剧集(带游标翻页)，返回 "name$id#name$id..."，id = workId|epKey|epIdx */
    private String fetchEpisodes(String workId, String lineKey) throws Exception {
        List<String> eps = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 60; guard++) {
            StringBuilder path = new StringBuilder("/api/v1/works/").append(workId).append("/episodes?page_size=500");
            if (lineKey != null && lineKey.length() > 0) path.append("&line_key=").append(urlEncode(lineKey));
            if (cursor != null && cursor.length() > 0) path.append("&cursor=").append(urlEncode(cursor));
            JSONObject resp = apiGet(path.toString());
            JSONArray items = resp.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject ep = items.getJSONObject(i);
                String name = ep.optString("name");
                if (name.length() == 0) name = "第" + (ep.optInt("index") + 1) + "集";
                eps.add(name + "$" + workId + "|" + ep.optString("key") + "|" + ep.optInt("index"));
            }
            String next = resp.optString("next_cursor", null);
            if (next == null || next.length() == 0) break;
            cursor = next;
        }
        return join(eps, "#");
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parseInt(pg, 1);
        try {
            JSONObject resp = apiGet("/api/v1/search?q=" + urlEncode(key) + "&page=" + page + "&page_size=20");
            JSONArray items = resp.optJSONArray("items");
            JSONArray list = new JSONArray();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject w = items.getJSONObject(i);
                    if (!w.has("id")) continue;
                    JSONObject vod = workToVod(w);
                    vod.put("type_name", kindName(w.optString("kind")));
                    list.put(vod);
                }
            }
            return new JSONObject().put("list", list).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String[] parts = id.split("\\|");
            long workId = Long.parseLong(parts[0]);
            String epKey = parts.length > 1 ? parts[1] : "";
            int epIdx = parts.length > 2 ? parseInt(parts[2], 0) : 0;

            JSONObject body = new JSONObject()
                    .put("work_id", workId)
                    .put("delivery_type", "playback");
            if (epKey.length() > 0) body.put("episode_key", epKey);
            body.put("episode_index", epIdx);

            JSONObject access = null;
            try {
                access = apiPost("/api/v1/media/access", body);
            } catch (Throwable t) {
                // 兜底：去掉 episode_key 重试
                try {
                    JSONObject b2 = new JSONObject()
                            .put("work_id", workId)
                            .put("delivery_type", "playback")
                            .put("episode_index", epIdx);
                    access = apiPost("/api/v1/media/access", b2);
                } catch (Throwable ignored) {
                }
            }
            String url = access == null ? "" : access.optString("url");
            o.put("parse", 0);
            o.put("playUrl", "");
            o.put("url", url);
        } catch (Throwable t) {
            o.put("parse", 0).put("url", "");
        }
        return o.toString();
    }

    // ==================== 加密 API 客户端 ====================

    private JSONObject apiGet(String path) throws Exception {
        return exchange("GET", path, null);
    }

    private JSONObject apiPost(String path, JSONObject body) throws Exception {
        return exchange("POST", path, body);
    }

    private JSONObject exchange(String method, String path, JSONObject body) throws Exception {
        Throwable lastError = null;
        for (int attempt = 0; attempt < hosts.length * 2; attempt++) {
            String origin = hosts[(hostIdx + attempt) % hosts.length];
            try {
                return exchangeOnce(origin, method, path, body);
            } catch (Throwable t) {
                lastError = t;
                if (t instanceof AuthError) rotateIdentity();
            }
        }
        if (lastError instanceof Exception) throw (Exception) lastError;
        throw new RuntimeException(lastError);
    }

    private static final class AuthError extends RuntimeException {
        AuthError(String msg) { super(msg); }
    }

    private JSONObject exchangeOnce(String origin, String method, String path, JSONObject body) throws Exception {
        long ts = System.currentTimeMillis() / 1000L;
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        String nonceB64 = b64u(nonce);

        byte[] bodyBytes;
        String contentType = "application/json";
        if (body != null && method.equals("POST")) {
            byte[] raw = body.toString().getBytes("UTF-8");
            String aad = "jp-api-request-v1\n" + method + "\n" + path + "\n" + ts + "\n" + nonceB64 + "\n" + deviceId;
            byte[] sealed = aesGcm(kReqBody, nonce, raw, aad.getBytes("UTF-8"), true);
            JSONObject env = new JSONObject()
                    .put("v", 1).put("alg", "A256GCM").put("ts", ts).put("data", b64u(sealed));
            bodyBytes = env.toString().getBytes("UTF-8");
            contentType = "application/vnd.jp.encrypted+json";
        } else {
            bodyBytes = new byte[0];
        }

        String shaHex = hex(sha256(bodyBytes));
        String signMsg = method + "\n" + path + "\n" + ts + "\n" + nonceB64 + "\n" + shaHex;
        String signature = b64u(hmac(kAuth, signMsg.getBytes("UTF-8")));

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(origin + path).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json, application/problem+json");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("X-Client-App", "jp");
            conn.setRequestProperty("X-Client-Platform", "android");
            conn.setRequestProperty("X-Device-ID", deviceId);
            conn.setRequestProperty("X-JP-Runtime-Authorization", "Bearer " + credential);
            conn.setRequestProperty("X-JP-Crypto-Version", "1");
            conn.setRequestProperty("X-JP-Timestamp", String.valueOf(ts));
            conn.setRequestProperty("X-JP-Nonce", nonceB64);
            conn.setRequestProperty("X-JP-Signature", signature);
            if (bodyBytes.length > 0) {
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                out.write(bodyBytes);
                out.flush();
                out.close();
            }

            int status = conn.getResponseCode();
            InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            byte[] raw = in == null ? new byte[0] : readAll(in);
            String respCrypto = conn.getHeaderField("X-JP-Crypto-Version");
            String respCt = conn.getContentType();

            if (status == 401) throw new AuthError(new String(raw, "UTF-8"));

            byte[] plain = raw;
            boolean encrypted = "1".equals(respCrypto)
                    || (respCt != null && respCt.startsWith("application/vnd.jp.encrypted+json"));
            if (status >= 200 && status < 300 && encrypted && status != 204 && status != 304 && raw.length > 0) {
                JSONObject env = new JSONObject(new String(raw, "UTF-8"));
                byte[] rn = b64d(env.optString("nonce"));
                byte[] data = b64d(env.optString("data"));
                String aad = "jp-api-response-v1\n" + method + "\n" + path + "\n" + status + "\n" + ts + "\n" + nonceB64 + "\n" + deviceId;
                plain = aesGcm(kRespBody, rn, data, aad.getBytes("UTF-8"), false);
            }
            if (status < 200 || status >= 300) {
                throw new RuntimeException("HTTP " + status + ": " + new String(plain, "UTF-8"));
            }
            if (plain.length == 0) return new JSONObject();
            return new JSONObject(new String(plain, "UTF-8"));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] aesGcm(byte[] key, byte[] iv, byte[] data, byte[] aad, boolean encrypt) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ==================== 基础工具 ====================

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] hmac(byte[] key, byte[] msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] B64REV = new int[128];

    static {
        Arrays.fill(B64REV, -1);
        for (int i = 0; i < B64.length; i++) B64REV[B64[i]] = i;
    }

    private static String b64u(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 4 + 2) / 3);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[(b0 << 4 | b1 >> 4) & 63]);
            if (i + 1 < data.length) sb.append(B64[(b1 << 2 | b2 >> 6) & 63]);
            if (i + 2 < data.length) sb.append(B64[b2 & 63]);
        }
        return sb.toString();
    }

    private static byte[] b64d(String s) {
        int len = s.length();
        int pad = (4 - len % 4) % 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len * 3 / 4 + 3);
        int acc = 0, bits = 0;
        for (int i = 0; i < len; i++) {
            int c = s.charAt(i);
            int v = c < 128 ? B64REV[c] : -1;
            if (v < 0) continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        if (pad > 2) { /* unreachable */ }
        return out.toByteArray();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(HEX[(b >> 4) & 15]);
            sb.append(HEX[b & 15]);
        }
        return sb.toString();
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    private static String kindName(String kind) {
        if (KIND_MOVIE.equals(kind)) return "电影";
        if (KIND_SERIES.equals(kind)) return "电视剧";
        if (KIND_ANIME.equals(kind)) return "动漫";
        if (KIND_VARIETY.equals(kind)) return "综艺";
        if (KIND_DOC.equals(kind)) return "纪录片";
        if (KIND_SHORT.equals(kind)) return "短剧";
        if (KIND_SPORTS.equals(kind)) return "体育";
        return kind;
    }
}
