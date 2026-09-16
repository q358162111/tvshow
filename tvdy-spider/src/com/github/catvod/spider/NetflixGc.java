package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 奈飞工厂  https://netflixgc.org
 * <p>
 * 模板：苹果CMS10 + dsn2（"短视"主题）。路由与实测结构：
 * <p>
 * 分类   /vodshow/{typeId}-----------.html            （仅壳，列表由 AJAX 填充）
 * 列表   POST /index.php/ds_api/vod                   参数 type/class/area/year/lang/version/state/
 * letter/time/level/weekday/by/page → JSON {pagecount,list[...]}
 * 详情   /voddetail/{id}.html
 * 源名   &lt;div class="anthology-tab"&gt;&lt;a class="swiper-slide"&gt;蓝光-4&lt;/a&gt;
 * 分集   &lt;ul class="anthology-list-play"&gt;&lt;a href="/vodplay/{id}-{sid}-{nid}.html"&gt;TC&lt;/a&gt;
 * 播放   /vodplay/{id}-{sid}-{nid}.html → player_aaaa={"url":"&lt;base64(URL编码 m3u8)&gt;"}
 * 搜索   /vodsearch/-------------.html?wd={kw}
 * <p>
 * 分类 id（站点实测导航）：1=电影 2=连续剧 3=漫剧 24=纪录片 23=综艺
 */
public class NetflixGc extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://netflixgc.org";
    private static final String API = "/index.php/ds_api/vod";

    private String host = DEFAULT_HOST;

    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "连续剧"}, {"3", "漫剧"}, {"23", "综艺"}, {"24", "纪录片"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "韩国", "日本",
            "泰国", "英国", "法国", "德国", "印度", "意大利", "西班牙", "加拿大", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "更早"};
    private static final String[][] FILTER_BY = {{"默认", "time"}, {"人气", "hits"}, {"评分", "score"}};

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
        JSONObject filters = new JSONObject();
        for (String[] c : DEFAULT_CLASSES) filters.put(c[0], buildFilters());
        return new JSONObject().put("class", classes).put("filters", filters).toString();
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

    // ==================== 分类（POST ds_api） ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;
        String by = seg(ext.get("by"));
        if (by.length() == 0) by = "time";

        StringBuilder body = new StringBuilder();
        param(body, "type", tid);
        param(body, "class", "");
        param(body, "area", seg(ext.get("area")));
        param(body, "year", seg(ext.get("year")));
        param(body, "lang", seg(ext.get("lang")));
        param(body, "version", "");
        param(body, "state", "");
        param(body, "letter", seg(ext.get("letter")));
        param(body, "time", "");
        param(body, "level", "0");
        param(body, "weekday", "");
        param(body, "by", by);
        param(body, "page", String.valueOf(page));

        String text = post(host + API, body.toString(), host + "/vodshow/" + tid + "-----------.html");

        JSONArray list = new JSONArray();
        int pagecount = 1;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray arr = o.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    String id = it.optString("vod_id", "");
                    if (id.length() == 0) id = first(it.optString("url", ""), "/voddetail/(\\d+)");
                    if (id.length() == 0) continue;
                    String name = clean(it.optString("vod_name", ""));
                    if (name.length() == 0) continue;
                    list.put(new JSONObject()
                            .put("vod_id", id)
                            .put("vod_name", name)
                            .put("vod_pic", fix(it.optString("vod_pic", "")))
                            .put("vod_remarks", clean(it.optString("vod_remarks", ""))));
                }
            }
            int pc = o.optInt("pagecount", 0);
            if (pc > 0) pagecount = pc;
        } catch (Throwable ignored) {
        }

        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", pagecount)
                .put("limit", 24)
                .put("total", pagecount * 24)
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
        String url = host + "/vodsearch/------------" + (page > 1 ? String.valueOf(page) : "") + "--1---.html?wd=" + enc(key);
        String html = unescape(get(url));

        JSONArray list = new JSONArray();
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<h3 class=\"slide-info-title[^\"]*\">([^<]+)</h3>").matcher(html);
        while (m.find()) {
            String name = clean(m.group(1));
            if (name.length() == 0) continue;
            String before = html.substring(Math.max(0, m.start() - 2000), m.start());
            String id = last(before, "/voddetail/(\\d+)\\.html");
            if (id.length() == 0 || !seen.add(id)) continue;
            String pic = last(before, "data-src=\"([^\"]+)\"");
            String after = html.substring(m.end(), Math.min(html.length(), m.end() + 1200));
            String remarks = first(after, "class=\"slide-info-remarks cor5\"[^>]*>([^<]+)<");
            list.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name)
                    .put("vod_pic", fix(pic))
                    .put("vod_remarks", clean(remarks)));
        }
        return new JSONObject().put("list", list).put("page", page)
                .put("pagecount", page + 1).put("limit", 20).put("total", 9999).toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0).trim();
        String html = unescape(get("/voddetail/" + id + ".html"));

        String name = clean(first(html, "<h3 class=\"slide-info-title[^\"]*\">([^<]+)</h3>"));
        if (name.length() == 0) name = clean(first(html, "data-name=\"([^\"]+)\""));
        if (name.length() == 0) {
            String t = clean(first(html, "<title>([^<]+)</title>"));
            int p = t.indexOf('_');
            if (p > 0) t = t.substring(0, p);
            p = t.indexOf(" - ");
            if (p > 0) t = t.substring(0, p);
            name = t.trim();
        }
        if (name.length() == 0) name = id;

        String pic = "";
        Matcher pm = Pattern.compile("<div class=\"detail-pic\">([\\s\\S]{0,800}?)</div>").matcher(html);
        if (pm.find()) pic = first(pm.group(1), "data-src=\"([^\"]+)\"");
        if (pic.length() == 0) {
            pic = firstIgnoreCase(html, "data-src=\"(https?://[^\"]+\\.(?:jpg|jpeg|png|webp))\"");
        }

        // slide-info 行：导演 / 演员 / 主演 / 类型 ...
        Map<String, String> data = new HashMap<String, String>();
        Matcher rm = Pattern.compile("<div class=\"slide-info hide partition\">([\\s\\S]{0,2000}?)</div>").matcher(html);
        while (rm.find()) {
            String blk = rm.group(1);
            int se = blk.indexOf("</strong>");
            String k = se >= 0 ? clean(blk.substring(0, se)) : "";
            String v = se >= 0 ? clean(blk.substring(se + "</strong>".length())) : "";
            k = k.replaceAll("[:：]", "").trim();
            v = trimSep(v);
            if (k.length() > 0 && v.length() > 0 && !data.containsKey(k)) data.put(k, v);
        }

        String year = "", area = "", lang = "", typeName = opt(data, "类型", "");
        Matcher dm = Pattern.compile("<a class=\"deployment[^\"]*\"[^>]*>([\\s\\S]{0,1500}?)</a>").matcher(html);
        if (dm.find()) {
            List<String> sp = new ArrayList<String>();
            Matcher sm = Pattern.compile("<span[^>]*>([\\s\\S]*?)</span>").matcher(dm.group(1));
            while (sm.find()) {
                String t = clean(sm.group(1));
                if (t.length() > 0 && !"详情".equals(t)) sp.add(t);
            }
            if (sp.size() > 0) year = sp.get(0);
            if (sp.size() > 1) area = sp.get(1);
            if (sp.size() > 2 && typeName.length() == 0) typeName = sp.get(2);
            if (sp.size() > 3) lang = sp.get(3);
        }

        String remarks = clean(first(html, "class=\"slide-info-remarks cor5\"[^>]*>([^<]+)<"));
        String content = clean(first(html, "<div id=\"height_limit\"[^>]*>([\\s\\S]*?)</div>"));
        String actor = opt(data, "演员", opt(data, "主演", ""));
        String director = opt(data, "导演", "");
        String score = clean(first(html, "<div class=\"fraction\">([^<]+)</div>"));

        // 播放源名称
        List<String> froms = new ArrayList<String>();
        Matcher tm = Pattern.compile("<div class=\"anthology-tab[^\"]*\">([\\s\\S]*?)<div class=\"anthology-list").matcher(html);
        if (tm.find()) {
            String region = tm.group(1);
            if (region.length() < 8000) {
                Matcher am = Pattern.compile("<a[^>]*>([\\s\\S]*?)</a>").matcher(region);
                while (am.find()) {
                    String s = clean(am.group(1));
                    if (s.length() > 0 && s.length() < 40) froms.add(s);
                }
            }
        }

        // 分集
        List<String> urls = new ArrayList<String>();
        Matcher lm = Pattern.compile("<ul class=\"anthology-list-play[^\"]*\"[^>]*>([\\s\\S]*?)</ul>").matcher(html);
        while (lm.find()) {
            Matcher em = Pattern.compile("<a[^>]+href=\"(/vodplay/[^\"]+)\"[^>]*>([^<]*)</a>").matcher(lm.group(1));
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em.find()) {
                String label = em.group(2).trim();
                if (label.length() == 0) label = "第" + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append(em.group(1));
                n++;
            }
            if (n > 0) urls.add(eps.toString());
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", fix(pic));
        vod.put("type_name", typeName);
        vod.put("vod_year", year);
        vod.put("vod_area", area);
        vod.put("vod_lang", lang);
        vod.put("vod_actor", actor);
        vod.put("vod_director", director);
        vod.put("vod_score", score);
        vod.put("vod_remarks", remarks);
        vod.put("vod_content", content);
        vod.put("vod_play_from", join(froms, "$$$", "奈飞工厂"));
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
            String html = unescape(get(url));
            String raw = first(html, "player_\\w+\\s*=\\s*\\{[\\s\\S]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
            if (raw.length() == 0) raw = first(html, "\"url\"\\s*:\\s*\"([^\"]+)\"");
            String real = decodePlay(raw);
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

    /** player_aaaa 的 url = base64(URL编码后的真实地址)，也可能是明文 */
    private String decodePlay(String raw) {
        if (raw == null) return "";
        String s = unescape(raw).trim();
        if (s.length() == 0) return "";
        if (s.startsWith("http")) return s;
        String b = b64(s);
        if (b.length() > 0) {
            if (b.startsWith("http")) return b;
            String u = urlDecode(b);
            if (u.startsWith("http")) return u;
        }
        String u2 = urlDecode(s);
        if (u2.startsWith("http")) return u2;
        return "";
    }

    private String b64(String s) {
        String t = s.trim();
        int pad = t.length() % 4;
        if (pad != 0) {
            StringBuilder sb = new StringBuilder(t);
            for (int i = 0; i < 4 - pad; i++) sb.append('=');
            t = sb.toString();
        }
        try {
            return new String(Base64.decode(t, Base64.DEFAULT), "UTF-8");
        } catch (Throwable ignored) {
        }
        try {
            return new String(Base64.decode(t, Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    // ==================== HTTP ====================

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
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
        if (!url.startsWith("http")) url = host + (url.startsWith("/") ? url : "/" + url);
        Map<String, String> h = header();
        probeHostOkHttp();

        if (sOkWithHeader != null) {
            try {
                Object o = sOkWithHeader.invoke(null, url, h);
                if (o instanceof String && ((String) o).length() > 0) return (String) o;
            } catch (Throwable ignored) {
            }
        }

        String text = transport("GET", url, h, null);
        if (text.length() > 0) return text;

        if (sOkPlain != null) {
            try {
                Object o = sOkPlain.invoke(null, url);
                if (o instanceof String) return (String) o;
            } catch (Throwable ignored) {
            }
        }
        return text;
    }

    private String post(String url, String form, String referer) {
        Map<String, String> h = header();
        if (referer != null) h.put("Referer", referer);
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        h.put("Accept", "application/json, text/javascript, */*; q=0.01");
        return transport("POST", url, h, form);
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
                OutputStream os = conn.getOutputStream();
                os.write(bs);
                os.flush();
                os.close();
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

    /** 站点偶发返回 JSON 转义过的 HTML（\/ \"），统一还原 */
    private String unescape(String s) {
        if (s == null) return "";
        if (s.indexOf("\\/") < 0 && s.indexOf("\\\"") < 0) return s;
        return s.replace("\\/", "/").replace("\\\"", "\"");
    }

    private void param(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) sb.append('&');
        sb.append(k).append('=').append(enc(v));
    }

    private String first(String text, String regex) {
        if (text == null || text.length() == 0) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String firstIgnoreCase(String text, String regex) {
        if (text == null || text.length() == 0) return "";
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String last(String text, String regex) {
        if (text == null || text.length() == 0) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        String r = "";
        while (m.find()) r = m.group(1);
        return r;
    }

    private String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("\\s+", " ").trim();
        return trimSep(t);
    }

    private String trimSep(String t) {
        if (t == null) return "";
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
        String v = data.get(key);
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
