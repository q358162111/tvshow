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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A123TV  https://a123tv.com   （繁体镜像 tw.a123tv.com）
 * <p>
 * 模板：自制 w4 模板（苹果CMS 数据源，路由全部用 slug，与 stui 系无关）：
 * <pre>
 *   首页     /
 *   分类     /t/{typeId}.html                  第 P 页 → /t/{typeId}/p{P}.html
 *   搜索     /s/{urlEncode(key)}.html          第 P 页 → /s/{urlEncode(key)}/p{P}.html
 *   详情     /v/{slug}.html
 *   分集     /v/{slug}/{ttl}z{n}.html          ← 0 基下标
 *   播放     GET 分集页 → div.w4-player[data-src] 里就是真实 m3u8（无需二次跳转）
 * </pre>
 * <p>
 * 几个实测结论（决定了本实现的写法）：
 * <ol>
 *   <li><b>分集地址可直接构造</b>：线路项自带 {@code data-ttl="4kz36ex"} 与 {@code 共2集}，
 *       该线路的第 n 集就是 {@code /v/{slug}/{ttl}z{n}.html}（n 从 0 起）。越界下标不会 404，
 *       而是回落到第 01 集，所以必须严格按 {@code 共N集} 生成，不能多生成。</li>
 *   <li><b>线路极多</b>：单片可达 80+ 条线路（同一部剧 20 集/10 集/4 集版本混排），
 *       全量灌给客户端会把 vod_play_url 撑成几百 KB，故默认只取前 {@link #DEFAULT_LINES} 条，
 *       可用 {@code ext={"lines":50}} 放开。线路名后面拼上清晰度，方便挑源。</li>
 *   <li><b>分类页分页控件是滑动窗口</b>（固定 13 个页码，最大页 = max(p+6, 13)，且不带「末页」），
 *       拿不到真实总页数，故用「窗口最大页（有下一页则 +1）」作为下界，客户端可逐页向后探。</li>
 *   <li><b>CDN 无防盗链</b>：实测 8 个不同域名（gsuus / lfthirtytwo / 360zyx / bfikuncdn …）
 *       对 UA 与 Referer 均不设限，浏览器 UA 或 okhttp 均可直接播，无需本机中继。</li>
 * </ol>
 * <p>
 * 站点广告/成人分区（/t/15.html、/t/1307.html）已排除，不纳入分类与筛选。
 */
public class A123tv extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://a123tv.com";

    /** 单条影片最多保留几条线路，避免 vod_play_url 过大（ext 的 lines 可覆盖） */
    private static final int DEFAULT_LINES = 12;

    private String host = DEFAULT_HOST;
    private int maxLines = DEFAULT_LINES;

    /** 四大类：type_id 即 /t/{id}.html */
    private static final String[][] DEFAULT_CLASSES = {
            {"10", "电影"}, {"11", "连续剧"}, {"12", "综艺"}, {"13", "动漫"}};

    /** 子类筛选：{所属大类, 子类 id, 名称}，取自各分类页 w4-meta 实测 */
    private static final String[][] SUB_TYPES = {
            {"10", "1001", "动作片"}, {"10", "1002", "喜剧片"}, {"10", "1003", "爱情片"},
            {"10", "1004", "科幻片"}, {"10", "1005", "恐怖片"}, {"10", "1006", "剧情片"},
            {"10", "1007", "战争片"}, {"10", "1008", "纪录片"}, {"10", "1010", "动漫电影"},
            {"10", "1011", "奇幻片"}, {"10", "1013", "动画片"}, {"10", "1014", "犯罪片"},
            {"10", "1016", "悬疑片"}, {"10", "1019", "邵氏电影"}, {"10", "1022", "歌舞片"},
            {"10", "1024", "家庭片"}, {"10", "1025", "古装片"}, {"10", "1026", "历史片"},
            {"10", "1027", "4K电影"},
            {"11", "1101", "国产剧"}, {"11", "1102", "香港剧"}, {"11", "1103", "韩国剧"},
            {"11", "1104", "欧美剧"}, {"11", "1105", "台湾剧"}, {"11", "1106", "日本剧"},
            {"11", "1107", "海外剧"}, {"11", "1108", "泰国剧"}, {"11", "1110", "港台剧"},
            {"11", "1111", "日韩剧"},
            {"12", "1201", "内地综艺"}, {"12", "1202", "港台综艺"}, {"12", "1203", "日韩综艺"},
            {"12", "1204", "欧美综艺"}, {"12", "1205", "国外综艺"},
            {"13", "1301", "国产动漫"}, {"13", "1302", "日韩动漫"}, {"13", "1303", "欧美动漫"},
            {"13", "1305", "海外动漫"}};

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        if (extend != null && extend.trim().length() > 0) {
            String ext = extend.trim();
            try {
                if (ext.startsWith("{")) {
                    JSONObject o = new JSONObject(ext);
                    String h = o.optString("host", "");
                    if (h.length() > 0) host = normalize(h);
                    int lines = o.optInt("lines", 0);
                    if (lines > 0) maxLines = lines;
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
        for (String[] c : DEFAULT_CLASSES) filters.put(c[0], buildFilters(c[0]));
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    /** 本站没有地区/年份/语言等筛选，只有「类型」一组 */
    private JSONArray buildFilters(String tid) throws Exception {
        JSONArray value = new JSONArray();
        value.put(new JSONObject().put("n", "全部").put("v", ""));
        for (String[] s : SUB_TYPES) {
            if (!s[0].equals(tid)) continue;
            value.put(new JSONObject().put("n", s[2]).put("v", s[1]));
        }
        JSONArray filters = new JSONArray();
        filters.put(new JSONObject().put("key", "class").put("name", "类型").put("value", value));
        return filters;
    }

    @Override
    public String homeVideoContent() throws Exception {
        return new JSONObject().put("list", parseList(get("/"))).toString();
    }

    // ==================== 分类 ====================
    //  无筛选 /t/{tid}.html        有筛选 /t/{子类id}.html
    //  第 P 页 在 .html 前插 /p{P}

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = Math.max(1, parseInt(pg, 1));
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String cls = seg(ext.get("class"));
        String typeId = cls.length() > 0 ? cls : seg(tid);
        if (typeId.length() == 0) typeId = DEFAULT_CLASSES[0][0];

        String html = get("/t/" + typeId + (page > 1 ? "/p" + page : "") + ".html");
        return new JSONObject()
                .put("list", parseList(html))
                .put("page", page)
                .put("pagecount", parsePageCount(html, page))
                .put("limit", 36)
                .put("total", 999999)
                .toString();
    }

    // ==================== 搜索 ====================
    //  /s/{urlEncode(key)}.html     第 P 页 /s/{urlEncode(key)}/p{P}.html

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = Math.max(1, parseInt(pg, 1));
        String html = get("/s/" + enc(key) + (page > 1 ? "/p" + page : "") + ".html");
        return new JSONObject()
                .put("list", parseList(html))
                .put("page", page)
                .put("pagecount", parsePageCount(html, page))
                .put("limit", 36)
                .put("total", 999999)
                .toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String slug = slug(ids.get(0));
        String html = get("/v/" + slug + ".html");

        String name = clean(first(html, "<li class=\"on\">\\s*<h1>([^<]*)</h1>"));
        if (name.length() == 0) name = slug;

        // 类型：必须限定在面包屑里找 —— 页面顶部导航也有一堆 /t/ 链接，全局第一个匹配到的是「连续剧」
        // 面包屑形如 首页 / 电影 / 剧情片 / 片名，取其中最后一个 /t/ 链接即子类
        String type = "";
        String bread = first(html, "<div class=\"w4-bread\">([\\s\\S]*?)</div>");
        if (bread.length() > 0) {
            Matcher bm = Pattern.compile("<a href=\"/t/\\d+\\.html\">([^<]+)</a>").matcher(bread);
            while (bm.find()) type = bm.group(1).trim();
        }

        String pic = fix(first(html, "<div[^>]*class=\"w4-player\"[^>]*data-poster=\"([^\"]*)\""));
        if (pic.length() == 0) pic = fix(first(html, "data-poster=\"([^\"]*)\""));

        // 当前选中的线路（详情页 w4-episode-list 只渲染它自己的分集，标题是真实集名）
        String onCode = first(html, "class=\"w4-line-item on\"[^>]*href=\"/v/[^\"]*/([^/\"]+)\\.html\"");
        List<String> onTitles = episodeTitles(html);

        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        String year = "", remarks = "";
        int lineNo = 0;

        Matcher lm = Pattern.compile("<a class=\"w4-line-item[^\"]*\" href=\"(/v/[^\"]+?)\"[^>]*title=\"([^\"]*)\">([\\s\\S]{0,1200}?)</a>").matcher(html);
        while (lm.find()) {
            String href = lm.group(1);
            String lineName = lm.group(2).trim();
            String body = lm.group(3);

            String ttl = first(body, "data-ttl=\"([^\"]+)\"");
            if (ttl.length() == 0) ttl = first(href, "/([^/\"]+?)z\\d+\\.html");
            if (ttl.length() == 0 || !seen.add(ttl)) continue;

            int count = Math.max(1, parseInt(first(body, "共(\\d+)集"), 1));
            String quality = first(body, "<div class=\"i\">[^<]*?/\\s*([0-9]{3,4}[pPkK])");
            String alt = first(body, "alt=\"([^\"]*)\"");
            if (alt.length() == 0) alt = first(body, "<h3 class=\"t\">([^<]*)</h3>");

            // 片名 [1994][HD] → 年份 + 备注（取第一处 4 位年份 和 最后一个非年份方括号）
            if (year.length() == 0) year = first(alt, "\\[(\\d{4})\\]");
            if (remarks.length() == 0) {
                Matcher ym = Pattern.compile("\\[([^\\[\\]]+)\\]").matcher(alt);
                while (ym.find()) {
                    String v = ym.group(1).trim();
                    if (!v.matches("\\d{4}")) remarks = v;
                }
                if (remarks.length() == 0) remarks = quality;
            }

            if (froms.size() >= maxLines) break;
            lineNo++;

            // 集名：当前线路用站点给的真题名；其余线路按 共N集 生成
            List<String> titles = null;
            if (onCode.length() > 0 && onCode.startsWith(ttl) && onTitles.size() == count) titles = onTitles;

            StringBuilder eps = new StringBuilder();
            for (int n = 0; n < count; n++) {
                String label;
                if (titles != null) label = titles.get(n);
                else if (count == 1) label = "正片";
                else label = "第" + (count < 100 && n < 9 ? "0" : "") + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append("/v/").append(slug).append('/').append(ttl).append('z').append(n).append(".html");
            }

            String flag = lineName.length() > 0 ? lineName : ("线路" + lineNo);
            if (quality.length() > 0) flag = flag + "·" + quality;
            froms.add(flag);
            urls.add(eps.toString());
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", slug);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("type_name", type);
        vod.put("vod_year", year);
        vod.put("vod_area", "");
        vod.put("vod_actor", "");
        vod.put("vod_director", "");
        vod.put("vod_remarks", remarks);
        vod.put("vod_content", "");
        vod.put("vod_play_from", join(froms, "$$$", "暂无资源"));
        vod.put("vod_play_url", join(urls, "$$$", ""));

        JSONArray videos = new JSONArray();
        videos.put(vod);
        return new JSONObject().put("list", videos).toString();
    }

    /** 详情页里当前线路的分集名（形如 国语中字 / 第01集） */
    private List<String> episodeTitles(String html) {
        List<String> titles = new ArrayList<String>();
        String block = first(html, "<div[^>]*class=\"w4-episode-list[^\"]*\"[^>]*>([\\s\\S]*?)</div>");
        if (block.length() == 0) return titles;
        Matcher m = Pattern.compile("<a[^>]*>([^<]*)</a>").matcher(block);
        while (m.find()) {
            String t = m.group(1).trim();
            if (t.length() > 0) titles.add(t);
        }
        return titles;
    }

    // ==================== 播放 ====================
    //  分集页 HTML 里 div.w4-player 直接带 data-src="https://.../index.m3u8"，一次请求即可

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id == null ? "" : id.trim();

        if (!isVideo(url) && url.startsWith("/v/")) {
            String html = get(url);
            String src = first(html, "<div[^>]*class=\"w4-player\"[\\s\\S]{0,300}?data-src=\"([^\"]+)\"");
            if (src.length() == 0) src = first(html, "data-src=\"(https?://[^\"]+)\"");
            if (src.length() > 0) url = src.replace("\\/", "/");
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
    //  <div class="w4-item-wrap"><a class="w4-item" href="/v/{slug}.html">
    //    <figure class="w4-item-cover"><img data-src="//i1.a123tv.com/..jpg" alt="片名">
    //      <div class="r">1080p</div><div class="s"><i></i><span>97个线路</span></div></figure>
    //    <div class="w4-item-info"><div class="t" title="片名">片名</div>
    //      <div class="i">剧情片 / 2026年</div></div></a></div>

    private JSONArray parseList(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<a class=\"w4-item\" href=\"/v/([^\"/]+)\\.html\">([\\s\\S]{0,1000}?)</a>").matcher(html);
        while (m.find()) {
            String slug = m.group(1);
            if (slug.length() == 0 || !seen.add(slug)) continue;
            String body = m.group(2);

            String name = first(body, "<div class=\"t\"[^>]*title=\"([^\"]*)\"").trim();
            if (name.length() == 0) name = clean(first(body, "<div class=\"t\"[^>]*>([^<]*)</div>"));
            if (name.length() == 0) name = first(body, "alt=\"([^\"]*)\"").trim();
            if (name.length() == 0) continue;

            String pic = fix(first(body, "data-src=\"([^\"]+)\""));
            String remarks = first(body, "<div class=\"r\">([^<]*)</div>").trim();
            if (remarks.length() == 0) remarks = first(body, "<span>(\\d+个线路)</span>").trim();

            videos.put(new JSONObject()
                    .put("vod_id", slug)
                    .put("vod_name", name)
                    .put("vod_pic", pic)
                    .put("vod_remarks", remarks));
        }
        return videos;
    }

    /**
     * 分页控件是固定 13 格的滑动窗口（最大页 = max(p+6, 13)），且没有「末页」，
     * 因此只能拿到总页数下界：有「下一页」时真实末页 &gt; 窗口最大页。
     * 返回窗口最大页（+1）可让客户端每翻一页自动多解锁一页，避免一次性给出上万页。
     * 完全无分页控件（内容只有一页）时返回当前页。
     */
    private int parsePageCount(String html, int page) {
        if (html == null || html.length() == 0) return page;
        int idx = html.indexOf("w4-page");
        if (idx < 0) return page;
        String seg = html.substring(idx, Math.min(html.length(), idx + 4000));
        int max = 0;
        Matcher m = Pattern.compile("转到第(\\d+)页").matcher(seg);
        while (m.find()) max = Math.max(max, parseInt(m.group(1), 0));
        if (max == 0) {
            Matcher m2 = Pattern.compile("<a[^>]+href=\"/[^\"]*p(\\d+)\\.html\"").matcher(seg);
            while (m2.find()) max = Math.max(max, parseInt(m2.group(1), 0));
        }
        if (max == 0) return page;
        if (seg.contains("class=\"next\"")) max++;
        return Math.max(max, page);
    }

    // ==================== 工具 ====================

    /** vod_id 可能是 slug / slug.html / /v/slug.html / 完整 URL，统一成 slug */
    private String slug(String id) {
        String s = id == null ? "" : id.trim();
        if (s.length() == 0) return "";
        if (s.startsWith("http")) {
            s = s.replaceFirst("^https?://[^/]+", "");
            s = s.replaceFirst("^/v/", "");
        } else {
            s = s.replaceFirst("^/v/", "");
        }
        if (s.endsWith(".html")) s = s.substring(0, s.length() - 5);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

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

        String text = httpGet(url, h);
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
