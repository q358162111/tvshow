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
 * TV电影天堂  https://www.tvdy.xyz
 * 站点架构：苹果CMS10 + stui 模板（已对 2025-09 线上站点实测）
 * <p>
 * 导航: /                  →  ul.stui-header__menu  →  /vodtype/{slug}.html
 * 分类: /vodtype/{slug}-{page}.html   +  ?area=&class=&year=&by=&lang=
 * 搜索: /vodsearch/{wd}----------{page}---.html
 * 详情: /voddetail/{id}.html
 * 播放: /vodplay/{id}-{sid}-{nid}.html  →  var player_xxxx={ encrypt, url (base64+urlencode), ... }
 * <p>
 * 配置示例：
 * {"key":"tvdy","name":"tvdy","type":3,"api":"csp_TvDy",
 * "searchable":1,"quickSearch":1,"filterable":1,"jar":"TvDy.jar"}
 */
public class TvDy extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.tvdy.xyz";

    private String host = DEFAULT_HOST;

    /** 默认分类（slug 形式，与站点导航一致） */
    private static final String[][] DEFAULT_CLASSES = {
            {"dianying", "电影"}, {"dianshiju", "电视剧"},
            {"zongyi", "综艺"}, {"dongman", "动漫"}, {"tiyu", "体育"}};

    private static final String[] FILTER_CLASS = {"喜剧", "动作", "剧情", "爱情", "科幻", "悬疑", "惊悚", "恐怖",
            "犯罪", "同性", "音乐", "歌舞", "传记", "历史", "战争", "西部", "奇幻", "冒险", "灾难", "武侠", "短剧"};
    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "法国", "英国", "日本", "韩国",
            "泰国", "德国", "丹麦", "印度", "意大利", "西班牙", "菲律宾", "加拿大", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019",
            "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011", "2010", "更早"};
    private static final String[][] FILTER_BY = {{"全部", ""}, {"最新", "time"}, {"人气", "hits"},
            {"推荐", "level"}, {"评分", "score"}};

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
        List<String[]> nav = parseNav();
        if (nav.isEmpty()) {
            for (String[] c : DEFAULT_CLASSES) classes.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        } else {
            for (String[] c : nav) classes.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        }
        JSONObject filters = new JSONObject();
        for (int i = 0; i < classes.length(); i++) {
            filters.put(classes.getJSONObject(i).optString("type_id"), buildFilters());
        }
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return new JSONObject().put("list", parseList(get("/"))).toString();
    }

    private List<String[]> parseNav() {
        List<String[]> list = new ArrayList<String[]>();
        try {
            String html = get("/");
            String block = first(html, "<ul class=\"stui-header__menu\">([\\s\\S]*?)</ul>");
            if (block.length() == 0) return list;
            Matcher m = Pattern.compile("<a[^>]+href=\"/vodtype/([a-z0-9]+)\\.html\"[^>]*>([^<]{1,10})</a>").matcher(block);
            Set<String> seen = new LinkedHashSet<String>();
            while (m.find()) {
                String id = m.group(1);
                String name = m.group(2).trim();
                if (id.length() == 0 || name.length() == 0 || !seen.add(id)) continue;
                list.add(new String[]{id, name});
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 与站点 /vodshow/ 路由上真实的筛选组保持一致（站点没有“语言”筛选） */
    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();
        filters.put(group("class", "剧情", FILTER_CLASS));
        filters.put(group("area", "地区", FILTER_AREA));
        filters.put(group("year", "年份", FILTER_YEAR));
        filters.put(group("by", "排序", null));
        return filters;
    }

    private JSONObject group(String key, String name, String[] values) throws Exception {
        JSONArray value = new JSONArray();
        if (values == null) {
            for (String[] by : FILTER_BY) value.put(new JSONObject().put("n", by[0]).put("v", by[1]));
        } else {
            value.put(new JSONObject().put("n", "全部").put("v", ""));
            for (String v : values) value.put(new JSONObject().put("n", v).put("v", v));
        }
        return new JSONObject().put("key", key).put("name", name).put("value", value);
    }

    // ==================== 分类 ====================
    // 无筛选：/vodtype/{slug}-{page}.html
    // 有筛选：站点筛选用 /vodshow/ 路由（?query 站点不认）：
    //   /vodshow/{slug}-{area}-{by}-{class}-{4}-{5}-{6}-{7}-{page}-{9}-{10}-{year}.html
    //   字段位置由线上链接实测确定：area=1, by=2, class=3, page=8, year=11

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String area = seg(ext.get("area"));
        String by = seg(ext.get("by"));
        String cls = seg(ext.get("class"));
        String year = seg(ext.get("year"));

        String target;
        if (area.length() == 0 && by.length() == 0 && cls.length() == 0 && year.length() == 0) {
            target = host + "/vodtype/" + tid + "-" + page + ".html";
        } else {
            String[] f = {"", "", "", "", "", "", "", "", "", "", "", ""};
            f[0] = tid;
            f[1] = area;
            f[2] = by;
            f[3] = cls;
            f[8] = page > 1 ? String.valueOf(page) : "";
            f[11] = year;
            StringBuilder sb = new StringBuilder(host).append("/vodshow/").append(f[0]);
            for (int i = 1; i < f.length; i++) sb.append('-').append(enc(f[i]));
            target = sb.append(".html").toString();
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

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parseInt(pg, 1);
        String html = get("/vodsearch/" + enc(key) + "----------" + page + "---.html");
        JSONArray list = parseList(html);
        return new JSONObject().put("list", list)
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
        String html = get("/voddetail/" + id + ".html");

        String name = first(html, "<h1 class=\"title\">([^<]+)");
        if (name.length() == 0) name = id;
        // 年份：先取 <font color=...>（YYYY）</font>，再退化到 <h1 class="title">...（YYYY）...
        String year = first(html, "<font color=\"#[0-9a-fA-F]+\">（(\\d{4})）</font>");
        if (year.length() == 0) year = first(html, "<h1[^>]*class=\"title\"[^>]*>[^（(]*[（(](\\d{4})[）)]");

        String pic = firstIgnoreCase(html, "class=\"stui-content__thumb[^\"]*\"[^>]*>[\\s\\S]*?data-original=\"([^\"]+)\"");
        if (pic.length() == 0) pic = firstIgnoreCase(html, "data-original=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");

        Map<String, String> data = new HashMap<String, String>();
        Matcher dm = Pattern.compile("<p class=\"data[^\"]*\">([^：:<]+)[：:]([\\s\\S]*?)</p>").matcher(html);
        while (dm.find()) {
            String k = dm.group(1).trim();
            if (k.length() > 0 && !data.containsKey(k)) data.put(k, clean(dm.group(2)));
        }

        String content = clean(first(html, "<span class=\"detail-content\"[^>]*>([\\s\\S]*?)</span>"));
        if (content.length() == 0 || content.equals("...")) {
            content = clean(first(html, "<span class=\"detail-sketch\"[^>]*>([\\s\\S]*?)</span>"));
        }
        if (content.equals("...")) content = "";

        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Matcher lm = Pattern.compile("<h4[^>]*>([\\s\\S]*?)</h4>\\s*<ul class=\"stui-content__playlist[^\"]*\">([\\s\\S]*?)</ul>").matcher(html);
        while (lm.find()) {
            String src = clean(lm.group(1));
            if (src.length() == 0) continue;
            Matcher em = Pattern.compile("<a[^>]+href=\"(/vodplay/\\d+-\\d+-\\d+\\.html)\"[^>]*>([^<]*)</a>").matcher(lm.group(2));
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em.find()) {
                String label = em.group(2).trim();
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

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id;
        if (!isVideo(url)) {
            String html = get(id);
            Matcher m = Pattern.compile("player_aaaa\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*</script>").matcher(html);
            if (m.find()) {
                try {
                    JSONObject o = new JSONObject(m.group(1));
                    String u = o.optString("url", "");
                    int encrypt = o.optInt("encrypt", 0);
                    if (u.length() > 0) {
                        if (encrypt == 1) u = URLDecoder.decode(u, "UTF-8");
                        else if (encrypt == 2) u = URLDecoder.decode(new String(Base64.decode(u, Base64.DEFAULT), "UTF-8"), "UTF-8");
                        if (u.startsWith("http")) url = u;
                    }
                } catch (Throwable ignored) {
                }
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
            String id = first(tag, "href=\"/voddetail/(\\d+)\\.html");
            if (id.length() == 0 || !seen.add(id)) continue;
            String name = first(tag, "title=\"([^\"]*)\"").trim();
            if (name.length() == 0) continue;
            // data-original 在开标签里；pic-text / score 在 </a> 之后
            String pic = first(tag, "(?:data-original|src)=\"([^\"]*)\"");
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
        // 取 stui-page 段内 "X/Y" 中最大的 Y 才是真实总页数（避免被首页轮播之类的 X/3 干扰）
        int best = 0;
        Matcher m = Pattern.compile(">(\\d+)\\s*/\\s*(\\d+)<").matcher(seg);
        while (m.find()) {
            int y = parseInt(m.group(2), 0);
            if (y > best) best = y;
        }
        if (best > 0) return best;
        // 兜底：段内最大数字
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

    /**
     * 宿主 OkHttp 的签名各家 fork 不一致（takagen99/Box 只有 string(String)，
     * 没有 string(String, Map)），所以这里一律用反射探测，找不到就退回纯 JDK 实现，
     * 避免 NoSuchMethodError 被吞掉后全部返回空字符串。
     */
    private static boolean sOkProbed;
    private static Method sOkWithHeader;   // OkHttp.string(String, Map)
    private static Method sOkPlain;        // OkHttp.string(String)

    private static void probeHostOkHttp() {
        if (sOkProbed) return;
        sOkProbed = true;
        Class<?> cls;
        try {
            cls = Class.forName("com.github.catvod.net.OkHttp");
        } catch (Throwable ignored) {
            return;
        }
        try {
            sOkWithHeader = cls.getMethod("string", String.class, Map.class);
        } catch (Throwable ignored) {
        }
        try {
            sOkPlain = cls.getMethod("string", String.class);
        } catch (Throwable ignored) {
        }
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

    /**
     * 纯 JDK 实现，不依赖宿主的 OkHttp（各家 fork 签名不一）。
     * 宿主在后台线程调用 spider（见 JarLoader.loadClassLoader 里的 initThread.join()），
     * 因此这里不额外开线程，避免触发 d8 的接口 desugaring。
     */
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