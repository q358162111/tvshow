package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 * 88影视  https://www.movietv88.cc
 * <p>
 * 模板：苹果CMS10 + stui（与 tvdy 同一套模板，但路由细节不同）：
 *   分类     /vod/type/id/{1..4}/page/{P}/                          ← 1=电影 2=连续剧 3=综艺 4=动漫
 *   筛选     /vod/show/by/{by}/id/{typeId}/[year/..][lang/..][area/..][letter/..][page/..]/
 *            或 /vod/show/id/{typeId}/[year/..]...（by 默认 time）
 *            其中 typeId 是 show 子类型 id（1=全部电影、6=爱情片、7=喜剧片；4=全部连续剧、36=国产剧...）
 *   详情     /vod/detail/id/{N}/
 *   播放     /vod/play/id/{N}/sid/{S}/nid/{T}/
 *            → 内嵌 var player_aaaa={..., "url":"https://hn.bfvvs.com/play/{code}"}
 *            → GET 该 URL，正则提取 const vid = '(...m3u8)'           （m3u8 在二次页 HTML 内）
 *   搜索     后端已 500，本 spider 直接返回空
 */
public class Movietv88 extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.movietv88.cc";

    private String host = DEFAULT_HOST;

    /** 四大类：type_id 用字符串 slug（TVBox 完全支持），便于和筛选里的 show id 共存 */
    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "连续剧"}, {"3", "综艺"}, {"4", "动漫"}};

    /** 筛选 class 选项（n=显示中文, v=show id）。三大类的子类映射均来自 show/id/N 页实测 */
    private static final String[][] FILTER_CLASS_MOVIE = {
            {"1", "全部"}, {"6", "爱情片"}, {"7", "喜剧片"}, {"8", "动作片"}, {"9", "科幻片"},
            {"10", "恐怖片"}, {"11", "剧情片"}, {"12", "战争片"}, {"21", "纪录片"},
            {"22", "动画片"}, {"23", "悬疑片"}, {"24", "犯罪片"}, {"25", "奇幻片"},
            {"26", "邵氏电影"}, {"27", "魔幻片"}};
    private static final String[][] FILTER_CLASS_TV = {
            {"4", "全部"}, {"36", "国产剧"}, {"37", "港台剧"}, {"38", "欧美剧"}, {"39", "日韩剧"}, {"40", "海外剧"}};
    private static final String[][] FILTER_CLASS_GENERIC = {{"1", "全部"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "法国", "英国",
            "日本", "韩国", "泰国", "德国", "丹麦", "印度", "意大利", "西班牙", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "更早"};
    private static final String[] FILTER_LANG = {"国语", "英语", "粤语", "闽南语", "韩语", "日语", "法语", "德语", "其它"};
    private static final char[] FILTER_LETTER = new char[27];  // A-Z + 0
    static {
        for (int i = 0; i < 26; i++) FILTER_LETTER[i] = (char) ('A' + i);
        FILTER_LETTER[26] = '0';
    }
    private static final String[][] FILTER_BY = {{"全部", ""}, {"最新", "time"}, {"人气", "hits"}, {"评分", "score"}};

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
        for (String[] c : DEFAULT_CLASSES) classes.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        JSONObject filters = new JSONObject();
        for (int i = 0; i < classes.length(); i++) {
            String tid = classes.getJSONObject(i).optString("type_id");
            filters.put(tid, buildFiltersFor(tid));
        }
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return new JSONObject().put("list", parseList(get("/"))).toString();
    }

    /** 筛选组：class 因 tid 而异（电影/连续剧用各自子类），其余全部共用 */
    private JSONArray buildFiltersFor(String tid) throws Exception {
        String[][] classOpt;
        if ("1".equals(tid)) classOpt = FILTER_CLASS_MOVIE;
        else if ("2".equals(tid)) classOpt = FILTER_CLASS_TV;
        else classOpt = FILTER_CLASS_GENERIC;

        JSONArray filters = new JSONArray();
        // class 筛选
        JSONArray classVal = new JSONArray();
        for (String[] kv : classOpt) classVal.put(new JSONObject().put("n", kv[1]).put("v", kv[0]));
        filters.put(new JSONObject().put("key", "class").put("name", "类型").put("value", classVal));

        // area
        JSONArray areaVal = new JSONArray();
        areaVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String a : FILTER_AREA) areaVal.put(new JSONObject().put("n", a).put("v", a));
        filters.put(new JSONObject().put("key", "area").put("name", "地区").put("value", areaVal));

        // year
        JSONArray yearVal = new JSONArray();
        yearVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String y : FILTER_YEAR) yearVal.put(new JSONObject().put("n", y).put("v", y));
        filters.put(new JSONObject().put("key", "year").put("name", "年份").put("value", yearVal));

        // lang
        JSONArray langVal = new JSONArray();
        langVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String l : FILTER_LANG) langVal.put(new JSONObject().put("n", l).put("v", l));
        filters.put(new JSONObject().put("key", "lang").put("name", "语言").put("value", langVal));

        // letter
        JSONArray letVal = new JSONArray();
        letVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (char c : FILTER_LETTER) {
            String s = String.valueOf(c);
            letVal.put(new JSONObject().put("n", s).put("v", s.equals("0") ? "0-9" : s));
        }
        filters.put(new JSONObject().put("key", "letter").put("name", "字母").put("value", letVal));

        // by
        JSONArray byVal = new JSONArray();
        for (String[] by : FILTER_BY) byVal.put(new JSONObject().put("n", by[0]).put("v", by[1]));
        filters.put(new JSONObject().put("key", "by").put("name", "排序").put("value", byVal));

        return filters;
    }

    // ==================== 分类 ====================
    //  无筛选：/vod/type/id/{tid}/page/{pg}/
    //  有筛选：/vod/show/by/{by}/id/{typeId}/[year/Y/][lang/L/][area/A/][letter/LET/][page/P/]
    //         （by 默认 time 时为 /vod/show/id/{typeId}/...）

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String cls = seg(ext.get("class"));   // show 子类型 id
        String area = seg(ext.get("area"));
        String year = seg(ext.get("year"));
        String lang = seg(ext.get("lang"));
        String letter = seg(ext.get("letter"));
        String by = seg(ext.get("by"));

        String target;
        boolean hasFilter = cls.length() > 0 || area.length() > 0 || year.length() > 0
                || lang.length() > 0 || letter.length() > 0 || by.length() > 0;

        if (!hasFilter) {
            // /vod/type/id/{tid}/page/{P}/
            target = host + "/vod/type/id/" + tid + "/page/" + page + "/";
        } else {
            // show 子类型 id：filter.class 给的是 show id；若用户没选 class，则用该大类的 show id（1=全部/4=全部连续剧）
            String typeId = cls.length() > 0 ? cls : ("1".equals(tid) ? "1" : ("2".equals(tid) ? "4" : ("3".equals(tid) ? "1" : "4")));
            String useBy = by.length() == 0 ? "time" : by;

            // URL: /vod/show/by/{useBy}/id/{typeId}/[year/..][lang/..][area/..][letter/..][page/..]
            StringBuilder sb = new StringBuilder(host).append("/vod/show/by/").append(useBy).append("/id/").append(typeId).append("/");
            if (year.length() > 0) sb.append("year/").append(year).append("/");
            if (lang.length() > 0) sb.append("lang/").append(enc(lang)).append("/");
            if (area.length() > 0) sb.append("area/").append(enc(area)).append("/");
            if (letter.length() > 0) sb.append("letter/").append(enc(letter)).append("/");
            if (page > 1) sb.append("page/").append(page).append("/");
            target = sb.toString();
        }

        String html = get(target);
        JSONArray list = parseList(html);
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", parsePageCount(html))
                .put("limit", 90)
                .put("total", 999999)
                .toString();
    }

    // ==================== 搜索 ====================
    // 该站搜索后端 500，本接口直接返回空列表

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return new JSONObject().put("list", new JSONArray()).put("page", 1)
                .put("pagecount", 0).put("limit", 90).put("total", 0).toString();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String html = get("/vod/detail/id/" + id + "/");

        String name = first(html, "<h1[^>]*class=\"title\"[^>]*>([^<]+)");
        if (name.length() == 0) name = id;

        String year = first(html, "<span[^>]*>(\\d{4})</span>");
        if (year.length() == 0) {
            year = first(html, "<h1[^>]*class=\"title\"[^>]*>[^（(]*[（(](\\d{4})[）)]");
        }

        String pic = firstIgnoreCase(html, "class=\"lazyload\"[^>]*data-original=\"([^\"]+)\"");
        if (pic.length() == 0) pic = firstIgnoreCase(html, "data-original=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");
        // style="...background-image: url(...)" 形式
        if (pic.length() == 0) pic = firstIgnoreCase(html, "background-image:\\s*url\\(([^)]+\\.(?:jpg|jpeg|png|webp))\\)");

        Map<String, String> data = new HashMap<String, String>();
        Matcher dm = Pattern.compile("<p class=\"data[^\"]*\"[^>]*>\\s*<span[^>]*>([^：:<]+)</span>([\\s\\S]*?)</p>").matcher(html);
        while (dm.find()) {
            String k = dm.group(1).trim();
            String v = clean(dm.group(2));
            if (k.length() > 0 && v.length() > 0 && !data.containsKey(k)) data.put(k, v);
        }
        // 也支持直接 text-muted span（站点可能没 data class）
        if (!data.containsKey("主演")) {
            Matcher tm = Pattern.compile("<span[^>]*>主演：?</span>([\\s\\S]*?)</p>").matcher(html);
            if (tm.find()) data.put("主演", clean(tm.group(1)));
        }

        String content = clean(first(html, "<span class=\"detail-content\"[^>]*>([\\s\\S]*?)</span>"));
        if (content.length() == 0) content = clean(first(html, "<div class=\"detail\"[^>]*>([\\s\\S]*?)</div>"));

        // 播放源 & 分集列表（多 sid 对应多线路，每条下挂 nid=分集号）
        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Matcher lm = Pattern.compile("<h4[^>]*>([\\s\\S]*?)</h4>\\s*<ul class=\"stui-content__playlist[^\"]*\"[^>]*>([\\s\\S]*?)</ul>").matcher(html);
        while (lm.find()) {
            String src = clean(lm.group(1));
            if (src.length() == 0) continue;
            Matcher em = Pattern.compile("<a[^>]+href=\"(/vod/play/id/\\d+/sid/(\\d+)/nid/(\\d+)/?)\"[^>]*>([^<]*)</a>").matcher(lm.group(2));
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em.find()) {
                String label = em.group(4).trim();
                if (label.length() == 0) label = "第" + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append(em.group(1));
                n++;
            }
            if (n == 0) continue;
            froms.add(src);
            urls.add(eps.toString());
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", fix(pic));
        vod.put("type_name", opt(data, "类型", ""));
        vod.put("vod_year", year.length() > 0 ? year : opt(data, "年份", ""));
        vod.put("vod_area", opt(data, "地区", ""));
        vod.put("vod_actor", opt(data, "主演", ""));
        vod.put("vod_director", opt(data, "导演", ""));
        vod.put("vod_remarks", opt(data, "更新", ""));
        vod.put("vod_content", content);
        vod.put("vod_play_from", join(froms, "$$$", "暂无资源"));
        vod.put("vod_play_url", join(urls, "$$$", ""));

        JSONArray videos = new JSONArray();
        videos.put(vod);
        return new JSONObject().put("list", videos).toString();
    }

    // ==================== 播放 ====================
    //   /vod/play/id/{N}/sid/{S}/nid/{T}/ → 内嵌 player_aaaa.url = "https://.../play/{code}"
    //   GET 该 URL → HTML 内 const vid = '(...m3u8)' ← 真实 m3u8

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id;

        if (!isVideo(url) && url.startsWith("/vod/play/id/")) {
            String html = get(url);
            // 优先从 player_xxxx 块里取 url，避免被 maccms 自身的 url 配置干扰
            String playUrl = "";
            Matcher m = Pattern.compile("player_\\w+\\s*=\\s*\\{[\\s\\S]*?\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
            if (m.find()) playUrl = m.group(1).replace("\\/", "/");
            // 兜底：含 m3u8/mp4 的 url 字段
            if (playUrl.length() == 0) {
                Matcher m2 = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+\\.(?:m3u8|mp4)[^\"]*)\"").matcher(html);
                if (m2.find()) playUrl = m2.group(1).replace("\\/", "/");
            }

            if (playUrl.length() > 0) {
                String hop = get(playUrl);
                String m3 = first(hop, "const\\s+vid\\s*=\\s*['\"](https?://[^'\"]+\\.m3u8)['\"]");
                if (m3.length() > 0) url = m3;
                else if (isVideo(playUrl)) url = playUrl;
            }
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

    private JSONArray parseList(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<a[^>]+class=\"stui-vodlist__thumb[^\"]*\"[^>]*>").matcher(html);
        while (m.find()) {
            String tag = m.group();
            String id = first(tag, "href=\"/vod/detail/id/(\\d+)/?\"");
            if (id.length() == 0 || !seen.add(id)) continue;
            String name = first(tag, "title=\"([^\"]*)\"").trim();
            if (name.length() == 0) continue;
            String pic = first(tag, "data-original=\"([^\"]+)\"");
            if (pic.length() == 0) pic = first(tag, "src=\"([^\"]+)\"");
            // 也吃 style="background-image: url(...)"
            if (pic.length() == 0) pic = first(tag, "background-image:\\s*url\\(([^)]+)\\)");
            String tail = html.substring(m.end(), Math.min(html.length(), m.end() + 400));
            String remarks = first(tail, "<span[^>]*class=\"[^\"]*pic-text[^\"]*\"[^>]*>([^<]+)<");
            if (remarks.length() == 0) remarks = first(tail, "<span[^>]*class=\"[^\"]*score[^\"]*\"[^>]*>([^<]+)<");
            videos.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name)
                    .put("vod_pic", fix(pic))
                    .put("vod_remarks", remarks.trim()));
        }
        return videos;
    }

    private int parsePageCount(String html) {
        if (html == null || html.length() == 0) return 9999;
        int idx = html.indexOf("stui-page");
        String seg = idx >= 0 ? html.substring(idx) : html;
        int best = 0;
        Matcher m = Pattern.compile(">(\\d+)\\s*/\\s*(\\d+)<").matcher(seg);
        while (m.find()) {
            int y = parseInt(m.group(2), 0);
            if (y > best) best = y;
        }
        if (best > 0) return best;
        int max = 0;
        Matcher m2 = Pattern.compile(">(\\d+)<").matcher(seg);
        while (m2.find()) max = Math.max(max, parseInt(m2.group(1), 0));
        return max > 0 ? max : 9999;
    }

    // ==================== 工具（与 TvDy 等价） ====================

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
            } catch (Throwable ignored) {}
        }

        String text = httpGet(url, h);
        if (text.length() > 0) return text;

        if (sOkPlain != null) {
            try {
                Object o = sOkPlain.invoke(null, url);
                if (o instanceof String) return (String) o;
            } catch (Throwable ignored) {}
        }
        return text;
    }

    private String httpGet(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
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

    private String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("\\s+", " ").trim();
        return t.replaceAll("^[：:，,、]+", "").replaceAll("[：:，,、]+$", "").trim();
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