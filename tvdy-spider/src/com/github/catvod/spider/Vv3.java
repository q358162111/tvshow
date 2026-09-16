package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vv3nwjk  https://vv3nwjk.com
 * <p>
 * Next.js(React Server Components flight) 站点，列表与详情都藏在 self.__next_f.push
 * 的转义 JSON 里，故统一先 unescape(\\" -> "、\\/ 等) 再按大括号配对截取对象。
 * <p>
 * 分类   /vod/show/id/{typeId}            第 1 页
 * /vod/show/id/{typeId}/page/{n}    第 n 页
 * 详情   /detail/{vodId}                  → JSON: vodName/vodClass/vodPic/vodActor/
 * vodDirector/vodContent/vodArea/vodLang/
 * vodYear/vodRemarks/vodDoubanScore/
 * episodeList[{nid,name,sort}]
 * 搜索   /vod/search/{关键字}             （不支持翻页，只有一页）
 * 播放   接口 /mw-movie/anonymous/v2/video/episode/url?id={vodId}&nid={nid}
 * 需请求头 t(毫秒时间戳) 与 sign = sha1(md5("id=..&nid=..&key={KEY}&t={t}"))
 * 返回 data.list[0].url → m3u8 直链
 * <p>
 * 分类 id（站点导航实测）：1=电影 2=电视剧 3=综艺 4=动漫 88=短剧
 */
public class Vv3 extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://vv3nwjk.com";
    /** 站点前端 JS 内固定的应用签名盐值 */
    private static final String SIGN_KEY = "cb808529bae6b6be45ecfab29a4889bc";
    private static final String API_PLAY = "/mw-movie/anonymous/v2/video/episode/url";

    private String host = DEFAULT_HOST;

    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "电视剧"}, {"3", "综艺"}, {"4", "动漫"}, {"88", "短剧"}};

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null && extend.trim().length() > 0) {
            String ext = extend.trim();
            try {
                if (ext.startsWith("{")) {
                    String h = new JSONObject(ext).optString("host", "");
                    if (h.length() > 0) host = normalize(h);
                } else if (ext.startsWith("http")) {
                    host = normalize(ext);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String normalize(String h) {
        h = h.trim();
        return h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        for (String[] c : DEFAULT_CLASSES) {
            classes.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        }
        return new JSONObject().put("class", classes).toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return categoryContent("1", "1", false, new HashMap<String, String>());
    }

    // ==================== 分类 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        String url = host + "/vod/show/id/" + tid + (page > 1 ? "/page/" + page : "");
        String text = unescape(get(url));

        JSONArray list = parseItems(text);
        int totalPage = parseInt(first(text, "\"totalPage\"\\s*:\\s*(\\d+)"), 0);
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", totalPage > 0 ? totalPage : page + 1)
                .put("limit", 48)
                .put("total", totalPage > 0 ? totalPage * 48 : 999999)
                .toString();
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parseInt(pg, 1);
        // 站点搜索只有一页（/vod/search/{kw}/page/2 → 404），多页时直接返回空
        if (page > 1) {
            return new JSONObject().put("list", new JSONArray()).put("page", page)
                    .put("pagecount", 1).put("limit", 48).put("total", 0).toString();
        }
        String text = unescape(get(host + "/vod/search/" + enc(key)));
        return new JSONObject().put("list", parseItems(text)).put("page", 1)
                .put("pagecount", 1).put("limit", 48).put("total", 9999).toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0).trim();
        String text = unescape(get(host + "/detail/" + id));

        JSONObject o = new JSONObject(detailJson(text));
        String vodId = o.optString("vodId", id);
        String name = clean(o.optString("vodName", ""));
        if (name.length() == 0) name = vodId;

        String remarks = o.optString("vodRemarks", "");
        String version = clean(o.optString("vodVersion", ""));
        if (remarks.length() == 0) remarks = version;
        else if (version.length() > 0) remarks = remarks + " " + version;

        String score = "";
        double db = o.optDouble("vodDoubanScore", 0);
        if (db > 0) score = String.valueOf(db);
        else {
            double s = o.optDouble("vodScore", 0);
            if (s > 0) score = String.valueOf(s);
        }

        List<String> eps = new ArrayList<String>();
        JSONArray epList = o.optJSONArray("episodeList");
        int auto = 0;
        if (epList != null) {
            for (int i = 0; i < epList.length(); i++) {
                JSONObject ep = epList.optJSONObject(i);
                if (ep == null) continue;
                String nid = ep.optString("nid", "");
                if (nid.length() == 0) continue;
                String label = clean(ep.optString("name", ""));
                if (label.length() == 0 || label.matches("\\d+")) label = "第" + (++auto) + "集";
                eps.add(label + "$/vod/play/" + vodId + "/sid/" + nid);
            }
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", vodId);
        vod.put("vod_name", name);
        vod.put("vod_pic", o.optString("vodPic", ""));
        vod.put("type_name", o.optString("typeName", ""));
        vod.put("vod_year", o.optString("vodYear", ""));
        vod.put("vod_area", o.optString("vodArea", ""));
        vod.put("vod_lang", o.optString("vodLang", ""));
        vod.put("vod_actor", clean(o.optString("vodActor", "")));
        vod.put("vod_director", clean(o.optString("vodDirector", "")));
        vod.put("vod_score", score);
        vod.put("vod_remarks", clean(remarks));
        vod.put("vod_content", clean(o.optString("vodContent", "")));
        vod.put("vod_play_from", "vv3nwjk");
        vod.put("vod_play_url", join(eps, "#"));

        JSONArray videos = new JSONArray();
        videos.put(vod);
        return new JSONObject().put("list", videos).toString();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String vodId = "", nid = "";
        Matcher m = Pattern.compile("(\\d+)/sid/(\\d+)").matcher(id == null ? "" : id);
        if (m.find()) {
            vodId = m.group(1);
            nid = m.group(2);
        }
        if (vodId.length() == 0 || nid.length() == 0) {
            // 兜底：直接从播放页 flight 数据里取 id / nid
            String page = unescape(get(host + (id != null && id.startsWith("/") ? id : "/vod/play/" + id)));
            vodId = first(page, "\\[\"id\",\"(\\d+)\"");
            nid = first(page, "\\[\"nid\",\"(\\d+)\"");
        }

        String url = "";
        if (vodId.length() > 0 && nid.length() > 0) {
            String query = "id=" + vodId + "&nid=" + nid;
            String ts = String.valueOf(System.currentTimeMillis());
            String sign = sha1(md5(query + "&key=" + SIGN_KEY + "&t=" + ts));
            Map<String, String> h = header();
            h.put("t", ts);
            h.put("sign", sign);
            h.put("deviceId", "web");
            h.put("authorization", "");
            String resp = unescape(transport("GET", host + API_PLAY + "?" + query, h, null));
            try {
                JSONObject data = new JSONObject(resp).optJSONObject("data");
                JSONArray arr = data == null ? null : data.optJSONArray("list");
                if (arr != null && arr.length() > 0) {
                    JSONObject it = arr.optJSONObject(0);
                    if (it != null) url = it.optString("url", "");
                }
            } catch (Throwable ignored) {
            }
        }
        if (url.length() == 0) url = id == null ? "" : id;

        JSONObject header = new JSONObject();
        header.put("User-Agent", UA);
        header.put("Referer", host + "/");
        return new JSONObject()
                .put("parse", isVideo(url) ? 0 : 1)
                .put("jx", 0)
                .put("playUrl", "")
                .put("url", url)
                .put("header", header.toString())
                .toString();
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return isVideo(url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    // ==================== flight 数据解析 ====================

    /** 列表：每项是无嵌套的 {"vodId":N,...} 小对象，直接逐个截取 */
    private JSONArray parseItems(String text) {
        JSONArray arr = new JSONArray();
        if (text == null || text.length() == 0) return arr;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("\\{\\s*\"vodId\"\\s*:\\s*\\d+[^{}]*\\}").matcher(text);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject(m.group());
                String id = o.optString("vodId", "");
                if (id.length() == 0 || !seen.add(id)) continue;
                String name = clean(o.optString("vodName", ""));
                if (name.length() == 0) continue;
                String remarks = clean(o.optString("vodRemarks", ""));
                if (remarks.length() == 0) remarks = clean(o.optString("vodVersion", ""));
                arr.put(new JSONObject()
                        .put("vod_id", id)
                        .put("vod_name", name)
                        .put("vod_pic", o.optString("vodPic", ""))
                        .put("vod_remarks", remarks));
            } catch (Throwable ignored) {
            }
        }
        return arr;
    }

    /** 详情对象含嵌套数组，需从第一个 {"vodId": 开始按大括号配对截取，且必须带 episodeList */
    private String detailJson(String text) {
        if (text == null) return "{}";
        Matcher m = Pattern.compile("\\{\\s*\"vodId\"\\s*:\\s*\\d+").matcher(text);
        while (m.find()) {
            String obj = balanced(text, m.start());
            if (obj.length() > 0 && obj.contains("\"episodeList\"")) return obj;
        }
        return "{}";
    }

    private String balanced(String s, int open) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(open, i + 1);
            }
        }
        return "";
    }

    // ==================== 签名 ====================

    private String md5(String s) {
        return digest("MD5", s);
    }

    private String sha1(String s) {
        return digest("SHA-1", s);
    }

    private String digest(String algo, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    // ==================== HTTP ====================

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
        h.put("Accept", "text/html,application/json,*/*;q=0.8");
        return h;
    }

    private static boolean sOkProbed;
    private static Method sOkWithHeader;
    private static Method sOkPlain;

    private static void probeHostOkHttp() {
        if (sOkProbed) return;
        sOkProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName("com.github.catvod.net.OkHttp");
        } catch (Throwable ignored) {
            return;
        }
        try { sOkWithHeader = cls.getMethod("string", String.class, Map.class); } catch (Throwable ignored) {}
        try { sOkPlain = cls.getMethod("string", String.class); } catch (Throwable ignored) {}
    }

    private String get(String url) {
        if (url == null || url.length() == 0) return "";
        String text = transport("GET", url, header(), null);
        if (text.length() > 0) return text;

        probeHostOkHttp();
        if (sOkWithHeader != null) {
            try {
                Object o = sOkWithHeader.invoke(null, url, header());
                if (o instanceof String && ((String) o).length() > 0) return (String) o;
            } catch (Throwable ignored) {
            }
        }
        if (sOkPlain != null) {
            try {
                Object o = sOkPlain.invoke(null, url);
                if (o instanceof String) return (String) o;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private String transport(String method, String url, Map<String, String> headers, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                byte[] bs = body.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bs.length);
                conn.getOutputStream().write(bs);
            }
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

    /** Next.js flight 数据里的 JSON 是 JS 字符串，先还原转义 */
    private String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").replace("\\r", "");
    }

    private String first(String text, String regex) {
        if (text == null || text.length() == 0) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("<[^>]+>", " ");
        t = t.replace("\\r\\n", " ").replace("\\n", " ").replace("\\r", " ")
                .replace("\\t", " ").replace("\\/", "/").replace("\\\"", "\"");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private String enc(String v) {
        if (v == null || v.length() == 0) return "";
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return v;
        }
    }

    private String join(List<String> list, String sep) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    private boolean isVideo(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv")
                || u.contains(".avi") || u.contains(".flv") || u.contains(".m4a");
    }
}
