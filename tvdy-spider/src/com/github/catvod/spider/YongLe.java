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
 * 永乐视频  https://www.cw2.net
 * （站点页面伪装为"瓜子影视"，实际为永乐视频：页面代码大量使用 ylsp 前缀
 *   ——永乐视频拼音缩写，页脚友链亦全是 ylys.tv / ylsp.pro 等永乐系域名）
 * 站点架构：苹果CMS10 + mxtheme 模板（module-* 类名，已对 2026-09 线上站点实测）
 * <p>
 * 导航: /vodtype/{1..4}/          →  电影 / 剧集 / 综艺 / 动漫
 * 列表: /vodshow/{id}-{area}-{by}-{class}-{lang}-{letter}-..-{page}-..-{year}/   (12 段，/ 结尾)
 *       实测段位: f[1]=area f[2]=by f[3]=class f[4]=lang f[5]=letter f[8]=page f[11]=year
 * 搜索: /vodsearch/{wd}----------{page}---/
 * 详情: /movie/{slug}-{id}/
 * 播放: /watch/{slug}-{id}-{sid}-{nid}/  →  var player_aaaa={"encrypt":0,"url":"...m3u8"}
 * <p>
 * 配置示例：
 * {"key":"yongle","name":"永乐视频","type":3,"api":"csp_YongLe",
 * "searchable":1,"quickSearch":1,"filterable":1,"jar":"./TvDy.jar"}
 */
public class YongLe extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 主域名。Cloudflare CDN，首次请求会下发 {@code server_session_xxx} cookie；
     * 真实可用的镜像/片库仅此一处。其它 {@code ylys.tv / ylsp.pro} 等只是推广入口（详情/播放 404），
     * 不加入 failover，避免被 CF/CDN 判定为异常源后跳到推广页导致无数据。
     * <p>
     * 如需临时切到备用推广域调试，可用 ext：{@code {"host":"https://www.ylys.tv"}}。
     */
    private static final String DEFAULT_HOST = "https://www.cw2.net";
    private static final String[] FALLBACK_HOSTS = {
            "https://www.ylys.tv",
            "https://www.ylys.cc",
            "https://www.ylsp.pro",
            "https://www.ylsp.one"};

    private String host = DEFAULT_HOST;

    /** 默认分类（与站点导航 /vodtype/{id}/ 一致） */
    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "剧集"}, {"3", "综艺"}, {"4", "动漫"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "日本", "韩国",
            "泰国", "英国", "欧美", "其它"};
    private static final String[] FILTER_LANG = {"国语", "粤语", "英语", "日语", "韩语",
            "泰语", "法语", "德语", "意大利语", "西班牙语", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011", "更早"};
    private static final String[][] FILTER_BY = {{"全部", ""}, {"最近更新", "time_update"},
            {"最新上架", "time_add"}, {"人气", "hits"}, {"评分", "score"}};

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        host = DEFAULT_HOST;

        if (extend != null && extend.trim().length() > 0) {
            String ext = extend.trim();
            try {
                if (ext.startsWith("{")) {
                    JSONObject cfg = new JSONObject(ext);
                    String h = cfg.optString("host", "");
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
        for (int i = 0; i < classes.length(); i++) {
            filters.put(classes.getJSONObject(i).optString("type_id"), buildFilters());
        }
        return new JSONObject().put("class", classes).put("filters", filters).toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        return new JSONObject().put("list", parsePosterList(get("/"))).toString();
    }

    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();
        filters.put(group("area", "地区", FILTER_AREA));
        filters.put(group("lang", "语言", FILTER_LANG));
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
    // 统一走 vodshow 12 段路由（分类页 /vodtype/{id}/ 无分页）：
    //   /vodshow/{id}-{area}-{by}-{class}-{lang}-{letter}-{7}-{page}-{9}-{10}-{11}-{year}/

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String area = seg(ext.get("area"));
        String by = seg(ext.get("by"));
        String cls = seg(ext.get("class"));
        String lang = seg(ext.get("lang"));
        String year = seg(ext.get("year"));

        String[] f = {"", "", "", "", "", "", "", "", "", "", "", ""};
        f[0] = tid;
        f[1] = area;
        f[2] = by;
        f[3] = cls;
        f[4] = lang;
        f[8] = String.valueOf(page);
        f[11] = year;
        StringBuilder sb = new StringBuilder(host).append("/vodshow/").append(f[0]);
        for (int i = 1; i < f.length; i++) sb.append('-').append(enc(f[i]));
        String target = sb.append('/').toString();

        String html = get(target);
        JSONArray list = parsePosterList(html);
        if (list.length() == 0 && page == 1) {
            // 兜底：无筛选的分类首页
            list = parsePosterList(get("/vodtype/" + tid + "/"));
        }
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", parsePageCount(html, page))
                .put("limit", 72)
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
        String html = get("/vodsearch/" + enc(key) + "----------" + page + "---/");
        JSONArray list = parseCardList(html);
        if (list.length() == 0) list = parsePosterList(html);
        return new JSONObject().put("list", list)
                .put("page", page)
                .put("pagecount", parsePageCount(html, page))
                .put("limit", 72)
                .put("total", 999999)
                .toString();
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);   // 形如 xianni-44693
        String html = get("/movie/" + id + "/");

        String name = first(html, "<h1[^>]*>([^<]+)</h1>");
        if (name.length() == 0) name = id;

        // 年份：上映/首播时间前 4 位
        String year = first(html, "上映：</span>\\s*<div class=\"module-info-item-content\">\\s*(\\d{4})");
        if (year.length() == 0) year = first(html, "<meta[^>]+property=\"og:release_date\"[^>]+content=\"(\\d{4})\"");

        // 封面：og:image 或第一张 data-original
        String pic = first(html, "<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"");
        if (pic.length() == 0) pic = first(html, "data-original=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");

        // 信息块：<span class="module-info-item-title">导演：</span><div class="module-info-item-content">..</div>
        Map<String, String> data = new HashMap<String, String>();
        Matcher dm = Pattern.compile("<span class=\"module-info-item-title\">([^：:]+)[：:]</span>\\s*<div class=\"module-info-item-content\">([\\s\\S]*?)</div>").matcher(html);
        while (dm.find()) {
            String k = dm.group(1).trim();
            if (k.length() > 0 && !data.containsKey(k)) data.put(k, clean(dm.group(2)));
        }

        String content = clean(first(html, "module-info-introduction-content\">([\\s\\S]*?)</div>"));
        if (content.length() == 0) content = first(html, "<meta[^>]+name=\"description\"[^>]+content=\"([^\"]+)\"");

        // 播放源 tab：module-tab-item 的 data-dropdown-value，与 module-play-list 顺序一一对应
        List<String> froms = new ArrayList<String>();
        Matcher tm = Pattern.compile("<div class=\"module-tab-item tab-item[^\"]*\"[^>]*data-dropdown-value=\"([^\"]*)\"").matcher(html);
        while (tm.find()) {
            String src = tm.group(1).trim();
            if (src.length() > 0) froms.add(src);
        }

        // 播放列表：<div class="module-play-list-content ...">... <a class="module-play-list-link" href="/watch/..">
        List<String> urls = new ArrayList<String>();
        Matcher lm = Pattern.compile("<div class=\"module-play-list-content[^\"]*\">([\\s\\S]*?)</div>\\s*(?=<div class=\"module-play-list\"|<div class=\"module-list|</div>)").matcher(html);
        while (lm.find()) {
            Matcher em = Pattern.compile("<a[^>]+class=\"module-play-list-link\"[^>]+href=\"(/watch/[^\"]+)\"[^>]*>([\\s\\S]*?)</a>").matcher(lm.group(1));
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em.find()) {
                String label = clean(em.group(2));
                if (label.length() == 0) label = "第" + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append(em.group(1));
                n++;
            }
            if (n > 0) urls.add(eps.toString());
        }

        // 若 tab 数量与列表数量不一致（正则截断兜底），以列表数为准补齐名称
        while (froms.size() < urls.size()) froms.add("线路" + (froms.size() + 1));
        if (froms.size() > urls.size()) froms = froms.subList(0, Math.max(0, urls.size()));

        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", fix(pic));
        vod.put("type_name", opt(data, "类型", ""));
        vod.put("vod_year", year);
        vod.put("vod_area", opt(data, "地区", ""));
        vod.put("vod_actor", opt(data, "主演", ""));
        vod.put("vod_director", opt(data, "导演", ""));
        vod.put("vod_remarks", opt(data, "连载", opt(data, "更新", "")));
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

    /**
     * 海报列表（首页 / 分类页）：
     * <a href="/movie/{slug}-{id}/" title="{name}" class="module-poster-item module-item">
     *   ... <div class="module-item-note">{remarks}</div>
     *   ... <img data-original="{pic}" alt="{name}">
     */
    private JSONArray parsePosterList(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<a[^>]+href=\"(/movie/[^\"]+)\"[^>]+title=\"([^\"]*)\"[^>]*class=\"module-poster-item[^\"]*\"[^>]*>([\\s\\S]*?)</a>").matcher(html);
        while (m.find()) {
            String link = m.group(1);
            String id = vid(link);
            String name = m.group(2).trim();
            if (id.length() == 0 || name.length() == 0 || !seen.add(id)) continue;
            String inner = m.group(3);
            String pic = first(inner, "data-original=\"([^\"]*)\"");
            String remarks = first(inner, "module-item-note\">([^<]+)<");
            videos.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name)
                    .put("vod_pic", fix(pic))
                    .put("vod_remarks", remarks.trim()));
        }
        return videos;
    }

    /**
     * 卡片列表（搜索结果）：
     * <a href="/movie/{slug}-{id}/" class="module-card-item-poster">
     *   ... <div class="module-item-note">{remarks}</div>
     *   ... <img data-original="{pic}" alt="{name}">
     */
    private JSONArray parseCardList(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<a[^>]+href=\"(/movie/[^\"]+)\"[^>]*class=\"module-card-item-poster[^\"]*\"[^>]*>([\\s\\S]*?)</a>").matcher(html);
        while (m.find()) {
            String id = vid(m.group(1));
            if (id.length() == 0 || !seen.add(id)) continue;
            String inner = m.group(2);
            String name = first(inner, "alt=\"([^\"]*)\"");
            if (name.length() == 0) continue;
            String pic = first(inner, "data-original=\"([^\"]*)\"");
            String remarks = first(inner, "module-item-note\">([^<]+)<");
            videos.put(new JSONObject()
                    .put("vod_id", id)
                    .put("vod_name", name.trim())
                    .put("vod_pic", fix(pic))
                    .put("vod_remarks", remarks.trim()));
        }
        return videos;
    }

    /** "/movie/xianni-44693/" → "xianni-44693" */
    private String vid(String link) {
        String id = first(link, "/movie/([^/]+)/?");
        return id;
    }

    /** 分页：取分页链接里最大的页码（...-{page}---/） */
    private int parsePageCount(String html, int cur) {
        if (html == null || html.length() == 0) return cur;
        int best = 0;
        Matcher m = Pattern.compile("href=\"[^\"]*?-(\\d+)---/\"[^>]*>").matcher(html);
        while (m.find()) {
            int n = parseInt(m.group(1), 0);
            if (n > best) best = n;
        }
        if (best > 0) return best;
        // 兜底：页面上的页码数字
        Matcher m2 = Pattern.compile("(?:第\\s*)(\\d+)\\s*/\\s*(\\d+)\\s*页").matcher(html);
        if (m2.find()) return parseInt(m2.group(2), cur);
        return cur;
    }

    // ==================== 工具 ====================

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
        return h;
    }

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

    /** 纯 JDK 实现，不依赖宿主的 OkHttp（各家 fork 签名不一） */
    private String httpGet(String url, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Connection", "close");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if ("User-Agent".equalsIgnoreCase(e.getKey())) continue;
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.length() > 0) return httpGet(loc, headers);
            }
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
        return t.replaceAll("^[：:，,、/]+", "").replaceAll("[：:，,、/]+$", "").trim();
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
