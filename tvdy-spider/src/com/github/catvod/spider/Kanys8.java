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
 * 影视大全（原 看影视） https://www.kanys8.com
 * <p>
 * 模板：苹果CMS10 + stui（ysdqst 样式，2026-09 实测）：
 *   分类     /filmlist/{slug}.html                          ← 第 1 页
 *            /filmlist/{slug}-{page}.html                    ← 第 N 页
 *   筛选     /filmshow/{type}[-{area}]----------.html        ← 12 parts: parts[0]=type,parts[1]=area,parts[11]=year
 *   搜索     /filmsearch/{urlEnc(key)}-------------.html     ← 第 1 页
 *            /filmsearch/{urlEnc(key)}----------{page}---.html← 第 N 页
 *   详情     /filmdetail/{id}.html
 *   播放     /filmplay/{id}-{sid}-{nid}.html                 → var player_aaaa={...,"url":"https://...m3u8"}
 * <p>
 * 筛选维度仅 type / area / year（实测 URL 中无 by / lang / other）。
 */
public class Kanys8 extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.kanys8.com";

    private String host = DEFAULT_HOST;

    /** 大类：type_id 用数字（tvbox 支持），slug 是 URL 第 1 段（filmlist/filmshow 共用） */
    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影", "dy"}, {"2", "电视剧", "dsj"}, {"3", "动漫", "dm"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "法国", "英国",
            "日本", "韩国", "泰国", "德国", "丹麦", "印度", "意大利", "西班牙", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "更早"};

    /** 子类型（实测 slug） */
    private static final String[][] FILTER_TYPE_MOVIE = {
            {"全部", "dy"}, {"剧情片", "jqp"}, {"喜剧片", "xjp"}, {"动作片", "dzp"},
            {"爱情片", "aqp"}, {"科幻片", "khp"}, {"悬疑片", "xyp"}, {"恐怖片", "kbp"},
            {"犯罪片", "fzp"}, {"战争片", "zzp"}, {"动画片", "dhp"}, {"奇幻片", "qhp"},
            {"冒险片", "mxp"}, {"惊悚片", "jsp"}, {"传记片", "cjp"}, {"历史片", "lsp"},
            {"纪录片", "jlp"}, {"音乐片", "ylp"}, {"歌舞片", "gwp"}, {"武侠片", "wxp"},
            {"古装片", "gzp"}, {"邵氏电影", "ssdy"}};
    private static final String[][] FILTER_TYPE_TV = {
            {"全部", "dsj"}, {"国产剧", "guocj"}, {"港台剧", "gangtj"}, {"欧美剧", "oumj"},
            {"日韩剧", "rihj"}, {"海外剧", "haiwj"}};
    private static final String[][] FILTER_TYPE_DM = {
            {"全部", "dm"}, {"国产动漫", "gcdm"}, {"日本动漫", "rbdm"}, {"欧美动漫", "omdm"},
            {"海外动漫", "hwdm"}};

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

    private String slugForTid(String tid) {
        for (String[] c : DEFAULT_CLASSES) if (c[0].equals(tid)) return c[2];
        return "dy";
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

    private JSONArray buildFiltersFor(String tid) throws Exception {
        String[][] typeOpt;
        if ("1".equals(tid)) typeOpt = FILTER_TYPE_MOVIE;
        else if ("2".equals(tid)) typeOpt = FILTER_TYPE_TV;
        else typeOpt = FILTER_TYPE_DM;

        JSONArray filters = new JSONArray();

        JSONArray typeVal = new JSONArray();
        for (String[] kv : typeOpt) typeVal.put(new JSONObject().put("n", kv[0]).put("v", kv[1]));
        filters.put(new JSONObject().put("key", "type").put("name", "类型").put("value", typeVal));

        JSONArray areaVal = new JSONArray();
        areaVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String a : FILTER_AREA) areaVal.put(new JSONObject().put("n", a).put("v", a));
        filters.put(new JSONObject().put("key", "area").put("name", "地区").put("value", areaVal));

        JSONArray yearVal = new JSONArray();
        yearVal.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String y : FILTER_YEAR) yearVal.put(new JSONObject().put("n", y).put("v", y));
        filters.put(new JSONObject().put("key", "year").put("name", "年份").put("value", yearVal));

        return filters;
    }

    // ==================== 分类 ====================
    //   无筛选: /filmlist/{slug}.html                ← 第 1 页
    //           /filmlist/{slug}-{page}.html          ← 第 N 页
    //   有筛选: /filmshow/{type}[-{area}]----------.html
    //           parts 固定 12 个: parts[0]=type, parts[1]=area, parts[2..10]='', parts[11]=year

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String type = seg(ext.get("type"));
        if (type.length() == 0) type = slugForTid(tid);

        String area = seg(ext.get("area"));
        String year = seg(ext.get("year"));

        String target;
        boolean hasFilter = area.length() > 0 || year.length() > 0;
        if (!hasFilter) {
            // 纯分类页：/filmlist/{slug}.html 或 /filmlist/{slug}-{page}.html
            StringBuilder sb = new StringBuilder(host).append("/filmlist/").append(type);
            if (page > 1) sb.append("-").append(page);
            sb.append(".html");
            target = sb.toString();
        } else {
            // 筛选页：/filmshow/{type}[-{area}]----------.html  (12 parts)
            String[] parts = new String[12];
            parts[0] = type;
            parts[1] = area.length() > 0 ? orDash(area) : "";
            parts[11] = year.length() > 0 ? year : "";
            target = host + "/filmshow/" + joinArr(parts, "-") + ".html";
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
    //   /filmsearch/{urlEnc(key)}----------{N}---.html
    //   （N=1 时也用此格式，分页链接实测就是这种）

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        if (key == null) key = "";
        key = key.trim();
        if (key.length() == 0) {
            return new JSONObject().put("list", new JSONArray()).put("page", 1)
                    .put("pagecount", 0).put("limit", 90).put("total", 0).toString();
        }
        int page = parseInt(pg, 1);
        // 统一格式：{key} 后 10 个 "-" + 页码 + "---"
        StringBuilder sb = new StringBuilder(host).append("/filmsearch/").append(orDash(key));
        for (int i = 0; i < 10; i++) sb.append("-");
        sb.append(page).append("---.html");
        String html = get(sb.toString());
        JSONArray list = parseList(html);
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", parsePageCount(html))
                .put("limit", 90)
                .put("total", 999999)
                .toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        String html = get("/filmdetail/" + id + ".html");

        String name = first(html, "<h1[^>]*class=\"title\"[^>]*>([^<]+)");
        if (name.length() == 0) name = id;

        String year = first(html, "<span[^>]*>(\\d{4})</span>");
        if (year.length() == 0) year = first(html, "<h1[^>]*class=\"title\"[^>]*>[^（(]*[（(](\\d{4})[）)]");

        String pic = firstIgnoreCase(html, "class=\"lazyload\"[^>]*data-original=\"([^\"]+)\"");
        if (pic.length() == 0) pic = firstIgnoreCase(html, "data-original=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");
        if (pic.length() == 0) pic = firstIgnoreCase(html, "background-image:\\s*url\\(([^)]+\\.(?:jpg|jpeg|png|webp))\\)");

        Map<String, String> data = new HashMap<String, String>();
        Matcher dm = Pattern.compile("<p class=\"data[^\"]*\"[^>]*>\\s*<span[^>]*>([^：:<]+)</span>([\\s\\S]*?)</p>").matcher(html);
        while (dm.find()) {
            String k = dm.group(1).trim();
            String v = clean(dm.group(2));
            if (k.length() > 0 && v.length() > 0 && !data.containsKey(k)) data.put(k, v);
        }
        if (!data.containsKey("主演")) {
            Matcher tm = Pattern.compile("<span[^>]*>主演：?</span>([\\s\\S]*?)</p>").matcher(html);
            if (tm.find()) data.put("主演", clean(tm.group(1)));
        }

        String content = clean(first(html, "<span class=\"detail-content\"[^>]*>([\\s\\S]*?)</span>"));
        if (content.length() == 0) content = clean(first(html, "<div class=\"detail\"[^>]*>([\\s\\S]*?)</div>"));

        // 实测结构: <h3 class="title">源名</h3> ...若干 div... <ul class="stui-content__playlist ...">...</ul>
        // 源名不紧邻 ul，故先定位 ul，再向前回溯最近的 h3/h4 文本
        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Matcher lm = Pattern.compile("<ul[^>]*class=\"stui-content__playlist[^\"]*\"[^>]*>([\\s\\S]*?)</ul>").matcher(html);
        int autoSrc = 0;
        while (lm.find()) {
            String src = "";
            String before = html.substring(Math.max(0, lm.start() - 800), lm.start());
            Matcher hm = Pattern.compile("<h3[^>]*>\\s*([^<]{1,40}?)\\s*</h3>").matcher(before);
            while (hm.find()) src = hm.group(1).trim();
            if (src.length() == 0) {
                Matcher h4m = Pattern.compile("<h4[^>]*>\\s*([^<]{1,40}?)\\s*</h4>").matcher(before);
                while (h4m.find()) src = h4m.group(1).trim();
            }
            if (src.length() == 0) src = "线路" + (++autoSrc);
            Matcher em = Pattern.compile("<a[^>]+href=\"(/filmplay/\\d+-(\\d+)-(\\d+)\\.html)\"[^>]*>([^<]*)</a>").matcher(lm.group(1));
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
    //   /filmplay/{id}-{sid}-{nid}.html → player_aaaa.url 已是直 m3u8（无二跳）

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id;

        if (!isVideo(url) && url.startsWith("/filmplay/")) {
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
            if (playUrl.length() > 0) url = playUrl;
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
        Matcher m = Pattern.compile("<a[^>]+class=\"[^\"]*stui-vodlist__thumb[^\"]*\"[^>]*>").matcher(html);
        while (m.find()) {
            String tag = m.group();
            String id = first(tag, "href=\"/filmdetail/(\\d+)\\.html\"");
            if (id.length() == 0 || !seen.add(id)) continue;
            String name = first(tag, "title=\"([^\"]*)\"").trim();
            if (name.length() == 0) continue;
            String pic = first(tag, "data-original=\"([^\"]+)\"");
            if (pic.length() == 0) pic = first(tag, "src=\"([^\"]+)\"");
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

    // ==================== 工具 ====================

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

    private String orDash(String v) {
        if (v == null) return "-";
        v = v.trim();
        if (v.isEmpty()) return "-";
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

    /** 数组拼接（用于 /filmshow/ 的固定 parts 模板拼接） */
    private String joinArr(String[] parts, String sep) {
        if (parts == null || parts.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts[i] == null ? "" : parts[i]);
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