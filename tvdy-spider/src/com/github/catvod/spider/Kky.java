package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 * 可可影视  https://www.kkys04.com
 * <p>
 * 站点有 JS 反爬（首次访问返回 HTTP 850 + 一段混淆脚本，需要暴力破解 cookie）：
 * const a0_0x2a54=['{CC}','{cookieName}=','...']; 数组被右旋 N 次后：
 * cookieName = arr[0] 去掉结尾 '='
 * cc         = arr[2]
 * n1         = parseInt(cc[0], 16)
 * 求最小 i 使 sha1(cc + i) 的第 n1、n1+1 字节分别为 0xb0、0xb
 * 得到 cookie: cookieName = cc + i
 * <p>
 * 分类   /show/{type}-{class}-{area}-{lang}-{year}-{order}-{page}.html（7 段，用 - 连接）
 * 首页导航 /channel/1.html 电影 /2.html 连续剧 /3.html 动漫 /4.html 综艺纪录 /6.html 短剧
 * 详情   /detail/{id}.html
 * 播放源  span.source-item-label（按顺序对应 episode-list 块）
 * 分集    div.episode-list > a.episode-item[href=/play/{id}-{sid}-{nid}.html]
 * 播放   /play/{id}-{sid}-{nid}.html → const playSource = { src: "https://...m3u8" }
 * 搜索   GET /search 取 token(t) → /search?k={kw}&page={n}&t={token}
 */
public class Kky extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.kkys04.com";

    private String host = DEFAULT_HOST;

    private String cookieName = "cdndefend_js_cookie";
    private String cookieValue = "";
    private int lastStatus = 0;
    private String token = "";

    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "连续剧"}, {"3", "动漫"}, {"4", "综艺纪录"}, {"6", "短剧"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "韩国", "日本",
            "泰国", "英国", "法国", "德国", "印度", "意大利", "西班牙", "加拿大", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012"};
    private static final String[][] FILTER_BY = {{"默认", "3"}, {"最新", "2"}, {"最热", "1"}, {"评分", "4"}};

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
        JSONObject result = new JSONObject().put("class", classes);
        if (filter) {
            JSONObject filters = new JSONObject();
            for (String[] c : DEFAULT_CLASSES) filters.put(c[0], buildFilters());
            result.put("filters", filters);
        }
        return result.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return categoryContent("1", "1", false, new HashMap<String, String>());
    }

    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();

        JSONArray areaVal = new JSONArray();
        areaVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String a : FILTER_AREA) areaVal.put(new JSONObject().put("n", a).put("v", a));
        filters.put(new JSONObject().put("key", "area").put("name", "地区").put("value", areaVal));

        JSONArray yearVal = new JSONArray();
        yearVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String y : FILTER_YEAR) yearVal.put(new JSONObject().put("n", y).put("v", y));
        filters.put(new JSONObject().put("key", "year").put("name", "年份").put("value", yearVal));

        JSONArray byVal = new JSONArray();
        for (String[] by : FILTER_BY) byVal.put(new JSONObject().put("n", by[0]).put("v", by[1]));
        filters.put(new JSONObject().put("key", "by").put("name", "排序").put("value", byVal));

        return filters;
    }

    // ==================== 分类 ====================
    //   /show/{type}-{class}-{area}-{lang}-{year}-{order}-{page}.html

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;
        String order = seg(ext.get("by"));
        if (order.length() == 0) order = "3";

        String[] fields = {tid, "", enc(seg(ext.get("area"))), "", enc(seg(ext.get("year"))), order, String.valueOf(page)};
        StringBuilder sb = new StringBuilder("/show/");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('-');
            sb.append(fields[i]);
        }
        sb.append(".html");

        String html = get(sb.toString());
        JSONArray list = parseCards(html);
        boolean hasNext = html != null && html.contains("page-item-next");
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", hasNext ? page + 1 : page)
                .put("limit", 18)
                .put("total", 999999)
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
        String t = token();
        String url = host + "/search?k=" + enc(key) + "&page=" + page + (t.length() > 0 ? "&t=" + enc(t) : "");
        String html = get(url);

        JSONArray list = new JSONArray();
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<a href=\"/detail/(\\d+)\\.html\"\\s+class=\"search-result-item\">([\\s\\S]*?)</a>").matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) continue;
            String blk = m.group(2);
            String name = clean(first(blk, "<div class=\"title\">([^<]*)</div>"));
            if (name.length() == 0) continue;
            String type = clean(first(blk, "<div class=\"search-result-item-header\">([\\s\\S]*?)</div>"));
            list.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name)
                    .put("vod_pic", pic(blk))
                    .put("vod_remarks", type));
        }
        boolean hasNext = html != null && html.contains("page-item-next");
        return new JSONObject().put("list", list).put("page", page)
                .put("pagecount", hasNext ? page + 1 : page).put("limit", 18).put("total", 9999).toString();
    }

    /** 搜索必须带一次性 token（藏在 /search 页面的 hidden input 里） */
    private String token() {
        try {
            String page = get("/search");
            String t = first(page, "name=\"t\"[^>]*value=\"([^\"]+)\"");
            if (t.length() == 0) t = first(page, "value=\"([A-Za-z0-9+/=]{16,})\"[^>]*name=\"t\"");
            if (t.length() > 0) token = t;
        } catch (Throwable ignored) {
        }
        return token;
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0).trim();
        String html = get("/detail/" + id + ".html");

        String name = "";
        Matcher tm = Pattern.compile("<div class=\"detail-title\">([\\s\\S]*?)</div>").matcher(html);
        if (tm.find()) {
            Matcher sm = Pattern.compile("<strong[^>]*>([^<]*)</strong>").matcher(tm.group(1));
            while (sm.find()) {
                String s = clean(sm.group(1));
                if (s.length() > 0 && hasCjk(s)) {
                    name = s;
                    break;
                }
            }
        }
        if (name.length() == 0) {
            String t = clean(first(html, "<title>([^<]*)</title>"));
            int p = t.indexOf('-');
            name = p > 0 ? t.substring(0, p).trim() : t;
        }
        if (name.length() == 0) name = id;

        String pic = "";
        Matcher pm = Pattern.compile("<div class=\"detail-pic\">([\\s\\S]{0,2000}?)</div>").matcher(html);
        if (pm.find()) pic = pic(pm.group(1));
        if (pic.length() == 0) pic = pic(html);

        String content = clean(first(html, "<div class=\"detail-desc\">([\\s\\S]*?)</div>"));

        Map<String, String> rows = new HashMap<String, String>();
        Matcher rm = Pattern.compile("<div class=\"detail-info-row-side\">\\s*([^<]*?)\\s*</div>\\s*<div class=\"detail-info-row-main\">\\s*([\\s\\S]*?)</div>").matcher(html);
        while (rm.find()) {
            String k = clean(rm.group(1)).replaceAll("[:：]", "").trim();
            String v = clean(rm.group(2));
            if (k.length() > 0 && v.length() > 0 && !rows.containsKey(k)) rows.put(k, v);
        }

        List<String> tags = new ArrayList<String>();
        Matcher gm = Pattern.compile("<a[^>]*class=\"detail-tags-item\"[^>]*>([^<]*)</a>").matcher(html);
        while (gm.find()) {
            String s = clean(gm.group(1));
            if (s.length() > 0) tags.add(s);
        }
        String year = "", area = "";
        StringBuilder typeName = new StringBuilder();
        for (String tag : tags) {
            if (year.length() == 0 && tag.matches("\\d{4}")) {
                year = tag;
            } else if (area.length() == 0 && isArea(tag)) {
                area = tag;
            } else if (typeName.length() == 0) {
                typeName.append(tag);
            } else {
                typeName.append(',').append(tag);
            }
        }

        // 播放源标签与分集块按文档顺序一一对应
        List<String> labels = new ArrayList<String>();
        Matcher lm = Pattern.compile("<span class=\"source-item-label\">([^<]*)</span>").matcher(html);
        while (lm.find()) {
            String s = clean(lm.group(1));
            if (s.length() > 0) labels.add(s);
        }

        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Matcher bm = Pattern.compile("<div class=\"episode-list\"[^>]*>([\\s\\S]*?)</div>").matcher(html);
        int idx = 0;
        while (bm.find()) {
            String src = idx < labels.size() ? labels.get(idx) : "线路" + (idx + 1);
            idx++;
            Matcher em = Pattern.compile("<a href=\"(/play/[^\"]+)\"[^>]*>\\s*<span>([^<]*)</span>").matcher(bm.group(1));
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em.find()) {
                String label = clean(em.group(2));
                if (label.length() == 0) label = "第" + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append(em.group(1));
                n++;
            }
            if (n == 0) continue;
            froms.add(src);
            urls.add(eps.toString());
        }

        String remarks = opt(rows, "备注", "");
        if (remarks.length() == 0) remarks = clean(first(html, "(更新至[^<\\s]{1,12})"));

        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("type_name", typeName.toString());
        vod.put("vod_year", year);
        vod.put("vod_area", area);
        vod.put("vod_actor", opt(rows, "演员", ""));
        vod.put("vod_director", opt(rows, "导演", ""));
        vod.put("vod_remarks", remarks);
        vod.put("vod_content", content);
        vod.put("vod_play_from", join(froms, "$$$", "可可影视"));
        vod.put("vod_play_url", join(urls, "$$$", ""));

        JSONArray videos = new JSONArray();
        videos.put(vod);
        return new JSONObject().put("list", videos).toString();
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id == null ? "" : id.trim();
        if (url.length() > 0 && !isVideo(url)) {
            String html = get(url);
            String real = first(html, "playSource\\s*=\\s*\\{[\\s\\S]{0,800}?src:\\s*\"([^\"]+)\"");
            if (real.length() == 0) real = first(html, "src:\\s*\"(https?://[^\"]+\\.(?:m3u8|mp4)[^\"]*)\"");
            if (real.length() > 0) url = real;
        }
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

    // ==================== 列表解析 ====================

    private JSONArray parseCards(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<div class=\"module-item\">\\s*<a href=\"/detail/(\\d+)\\.html\"[^>]*>([\\s\\S]*?)</a>").matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) continue;
            String blk = m.group(2);
            String name = clean(first(blk, "<div class=\"v-item-title\">([^<]+)</div>"));
            if (name.length() == 0) continue;
            String remarks = clean(first(blk, "<div class=\"v-item-bottom\">([\\s\\S]*?)</div>"));
            videos.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name)
                    .put("vod_pic", pic(blk))
                    .put("vod_remarks", remarks));
        }
        return videos;
    }

    /** 卡片里第一张 img 是站点占位图，取第一个非占位的 data-original */
    private String pic(String block) {
        if (block == null) return "";
        Matcher m = Pattern.compile("data-original=\"([^\"]+)\"").matcher(block);
        while (m.find()) {
            String u = m.group(1).trim();
            if (u.length() == 0) continue;
            if (u.contains("logo_placeholder") || u.contains("logo_")) continue;
            return fix(u);
        }
        return "";
    }

    private boolean isArea(String tag) {
        String[] keys = {"大陆", "香港", "台湾", "美国", "韩国", "日本", "泰国", "英国", "法国",
                "德国", "印度", "意大利", "西班牙", "加拿大", "其它", "国产"};
        for (String k : keys) if (tag.contains(k)) return true;
        return false;
    }

    private boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    // ==================== 反爬 ====================

    private String get(String url) {
        if (url == null || url.length() == 0) return "";
        if (!url.startsWith("http")) url = host + (url.startsWith("/") ? url : "/" + url);
        String body = request(url);
        if (lastStatus == 850) {
            if (solveChallenge(body)) body = request(url);
            else return "";
        }
        return body;
    }

    private String request(String url) {
        HttpURLConnection conn = null;
        lastStatus = 0;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8");
            conn.setRequestProperty("Referer", host + "/");
            if (cookieValue.length() > 0) conn.setRequestProperty("Cookie", cookieName + "=" + cookieValue);
            int code = conn.getResponseCode();
            lastStatus = code;
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

    /**
     * 复刻站点 850 挑战脚本：
     * const NAME=['{CC}','{cookieName}=','...']  被 }(NAME,0xN)) 右旋 N 次
     * 求最小 i，使 sha1(cc+i) 的 byte[n1]、byte[n1+1] 等于脚本里的两个常量
     */
    private boolean solveChallenge(String body) {
        if (body == null || body.length() == 0) return false;
        try {
            Matcher rm = Pattern.compile("\\}\\(\\s*([A-Za-z0-9_$]+)\\s*,\\s*0x([0-9a-fA-F]+)\\s*\\)\\)").matcher(body);
            if (!rm.find()) return false;
            String var = rm.group(1);
            int rot = Integer.parseInt(rm.group(2), 16);

            Matcher am = Pattern.compile("const\\s+" + Pattern.quote(var) + "\\s*=\\s*\\[([^\\]]*)\\]").matcher(body);
            if (!am.find()) return false;

            List<String> arr = new ArrayList<String>();
            Matcher sm = Pattern.compile("'([^']*)'").matcher(am.group(1));
            while (sm.find()) arr.add(sm.group(1));
            if (arr.size() < 3) return false;

            rot = ((rot % arr.size()) + arr.size()) % arr.size();
            for (int i = 0; i < rot; i++) arr.add(arr.remove(0));

            String cname = arr.get(0);
            if (cname.endsWith("=")) cname = cname.substring(0, cname.length() - 1);
            String cc = arr.get(2);
            if (cname.length() == 0 || cc.length() < 3) return false;

            Matcher tm = Pattern.compile("\\[n1\\]\\s*===\\s*0x([0-9a-fA-F]+)\\s*&&\\s*s\\[n1\\+0x1\\]\\s*===\\s*0x([0-9a-fA-F]+)").matcher(body);
            if (!tm.find()) return false;
            int b0 = Integer.parseInt(tm.group(1), 16);
            int b1 = Integer.parseInt(tm.group(2), 16);
            int n1 = Integer.parseInt(cc.substring(0, 1), 16);
            if (n1 < 0 || n1 + 1 > 19) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] seed = cc.getBytes("UTF-8");
            for (int i = 0; i < 2000000; i++) {
                md.reset();
                md.update(seed);
                md.update(String.valueOf(i).getBytes("UTF-8"));
                byte[] d = md.digest();
                if ((d[n1] & 0xff) == b0 && (d[n1 + 1] & 0xff) == b1) {
                    cookieName = cname;
                    cookieValue = cc + i;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================== 工具 ====================

    private String first(String text, String regex) {
        if (text == null || text.length() == 0) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("\\s+", " ").trim();
        return t.replaceAll("^[：:，,、·]+", "").replaceAll("[：:，,、·]+$", "").trim();
    }

    private String fix(String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return host + url;
        return url;
    }

    private String seg(String v) {
        return v == null ? "" : v.trim();
    }

    private String enc(String v) {
        if (v == null || v.length() == 0) return "";
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Throwable e) {
            return v;
        }
    }

    private String opt(Map<String, String> data, String key, String def) {
        String v = data == null ? null : data.get(key);
        return v == null || v.length() == 0 ? def : v;
    }

    private String join(List<String> list, String sep, String def) {
        if (list == null || list.isEmpty()) return def;
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
