package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * 站点架构：苹果CMS10 + stui 模板（接口与 URL 段位均经实测验证）
 * <p>
 * 分类: /vodshow/{id}-{area}-{by}-{class}-{lang}----{page}---{year}.html
 * 分类页: /vodtype/{id}.html       详情: /voddetail/{id}.html
 * 搜索: /vodsearch/{wd}----------{page}---.html
 * 播放: /vodplay/{id}-{sid}-{nid}.html  → var player_aaaa={...}，encrypt=2 时 url=base64(urlencode(直链))
 * <p>
 * 配置示例：
 * {"key":"电影天堂","name":"电影天堂","type":3,"api":"csp_TvDy",
 * "searchable":1,"quickSearch":1,"filterable":1,"jar":"xx.jar",
 * "ext":{"host":"https://www.tvdy.xyz"}}
 */
public class TvDy extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.tvdy.xyz";

    private String host = DEFAULT_HOST;

    /** 默认分类（站点导航解析失败时兜底） */
    private static final String[][] DEFAULT_CLASSES = {
            {"dianying", "电影"}, {"dianshiju", "电视剧"},
            {"zongyi", "综艺"}, {"dongman", "动漫"}, {"tiyu", "体育"}};

    private static final String[] FILTER_CLASS = {"喜剧", "动作", "剧情", "爱情", "科幻", "悬疑", "惊悚", "恐怖",
            "犯罪", "同性", "音乐", "歌舞", "传记", "历史", "战争", "西部", "奇幻", "冒险", "灾难", "武侠", "短剧"};
    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "法国", "英国", "日本", "韩国",
            "泰国", "德国", "丹麦", "印度", "意大利", "西班牙", "菲律宾", "加拿大", "其它"};
    private static final String[] FILTER_LANG = {"国语", "粤语", "英语", "韩语", "日语", "法语", "德语", "俄语",
            "泰语", "闽南语", "意大利语", "西班牙语", "葡萄牙语", "菲律宾语", "泰米尔语", "其它"};
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

    private JSONArray buildFilters() throws Exception {
        JSONArray filters = new JSONArray();
        filters.put(group("class", "剧情", FILTER_CLASS));
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

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;
        String[] parts = {tid, seg(ext.get("area")), seg(ext.get("by")), seg(ext.get("class")),
                seg(ext.get("lang")), "", "", "", String.valueOf(page), "", "", seg(ext.get("year"))};
        StringBuilder sb = new StringBuilder(host).append("/vodshow/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('-');
            sb.append(enc(parts[i]));
        }
        sb.append(".html");
        String html = get(sb.toString());
        JSONArray list = parseList(html);
        if (list.length() == 0) {
            // 兜底：普通分类页
            html = get("/vodtype/" + tid + (page > 1 ? "-" + page : "") + ".html");
            list = parseList(html);
        }
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", list.length() == 0 ? page : parsePageCount(html))
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
                .put("pagecount", list.length() == 0 ? page : parsePageCount(html))
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
        String year = first(html, "<font color=\"#[0-9a-fA-F]+\">（(\\d{4})）</font>");
        String pic = first(html, "class=\"stui-content__thumb[^\"]*\"[^>]*>[\\s\\S]*?data-original=\"([^\"]+)\"");
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
        String seg = idx >= 0 ? html.substring(idx) : "";
        String total = first(seg, ">(\\d+)\\s*/\\s*(\\d+)<");
        if (total.length() == 0) total = first(html, ">(\\d+)\\s*/\\s*(\\d+)<");
        int pages = parseInt(total, 0);
        if (pages > 0) return pages;
        Matcher m = Pattern.compile(">(\\d+)<").matcher(seg);
        int max = 0;
        while (m.find()) max = Math.max(max, parseInt(m.group(1), 0));
        return max > 0 ? max : 9999;
    }

    // ==================== 工具 ====================

    private Map<String, String> header() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", host + "/");
        return h;
    }

    private String get(String url) {
        if (url == null || url.length() == 0) return "";
        if (!url.startsWith("http")) url = host + (url.startsWith("/") ? url : "/" + url);
        try {
            String text = OkHttp.string(url, header());
            return text == null ? "" : text;
        } catch (Throwable e) {
            return "";
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

    /** 苹果CMS 12 段 URL 用：空值 => 空串 */
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
