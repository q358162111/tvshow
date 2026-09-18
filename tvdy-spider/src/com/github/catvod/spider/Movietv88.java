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
 * 88影视  https://www.88ystv.com
 * <p>
 * 模板：pc88ysw 自制模板（2026-09 实测，slug 路由，但内部保留了 vod-type-id / vod-play-id 数字路由）：
 *   导航    /                                         →  li.top-nav 内的 /vod-type-id-{1..4}-pg-1.html
 *   分类    /vod-type-id/{tid}/pg/{P}.html            ← 1=电影 2=电视剧 3=综艺 4=动漫
 *   分类筛选 (页内) /vod-type-id/{subId}/pg/1.html    ← 仅"按分类"项
 *   搜索    /vod-search-pg-{P}-wd-{urlEnc(wd)}[-area-...][-by-...][-typeid-...][-year-...].html
 *            （段位按字母顺序，可任意组合；HTTP 302 → /search/search.php?q=...）
 *   列表项   <li class="p1 m1"><a href="/{slug}/{yyyyMM}/{id}.html" title="..."><img data-original="..."></a>
 *            <span class="lzbz"><p class="name">{name}</p><p class="actor">{actors}</p>
 *                            <p class="actor">{type}</p><p class="actor">{year/area}</p></span>
 *            <p class="other"><i>{remarks}</i></p>
 *   详情    GET /{slug}/{yyyyMM}/{id}.html             → 解析 thumb + 简介 + 6 个播放源(stab81..stab86)
 *            播放项 <li><a href="/vod-play-id-{id}-src-{S}-num-{T}.html" title="{label}">
 *   播放    GET /vod-play-id-{id}-src-{S}-num-{N}.html  → 内嵌 var mac_url=unescape('%uXXXX%uXXXX%XX$token$from')
 *            + /js/playerconfig.js + /play/player.www.js + /player/{from}.js
 *            → 第三方 iframe (如 https://vip.jsjinfu.com:8443?url=...) 解 m3u8
 *            → 爬虫不模拟 JS，直接传播放页 URL 让客户端 parse=1 解析
 *   分页文本  当前:1/2674页
 */
public class Movietv88 extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String DEFAULT_HOST = "https://www.88ystv.com";

    private String host = DEFAULT_HOST;

    /** 4 大类 */
    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "电视剧"}, {"3", "综艺"}, {"4", "动漫"}};

    /** 4 大类的子类（与首页 hover-nav 一致） */
    private static final String[][] SUB_MOVIE = {
            {"1", "全部"}, {"5", "动作片"}, {"6", "喜剧片"}, {"7", "爱情片"}, {"8", "科幻片"},
            {"9", "恐怖片"}, {"10", "剧情片"}, {"11", "战争片"}, {"16", "纪录片"},
            {"17", "动画片"}, {"18", "悬疑片"}, {"19", "犯罪片"}, {"20", "奇幻片"},
            {"25", "邵氏电影"}, {"26", "魔幻片"}, {"27", "其他片"}};
    private static final String[][] SUB_TV = {
            {"2", "全部"}, {"12", "国产剧"}, {"13", "港台剧"}, {"14", "日韩剧"},
            {"15", "欧美剧"}, {"29", "其他剧"}};
    private static final String[][] SUB_ZY = {
            {"3", "全部"}, {"23", "内地综艺"}, {"24", "港台综艺"}, {"21", "日韩综艺"}, {"22", "欧美综艺"}};
    private static final String[][] SUB_DM = {
            {"4", "全部"}, {"30", "国产动漫"}, {"31", "日本动漫"}, {"32", "欧美动漫"}, {"33", "其他动漫"}};

    private static final String[] FILTER_AREA = {"大陆", "香港", "台湾", "美国", "法国", "英国",
            "日本", "韩国", "泰国", "德国", "丹麦", "印度", "意大利", "西班牙", "新加坡", "马来西亚", "俄罗斯", "其它"};
    private static final String[] FILTER_YEAR = {"2026", "2025", "2024", "2023", "2022", "2021",
            "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011",
            "2010", "2009", "2008", "2007", "2006", "2005", "2004", "2003", "2002", "2001", "2000",
            "1999", "1998", "1997", "1996", "1995", "更早"};
    private static final String[][] FILTER_BY = {{"最新", "time"}, {"人气", "hits"}, {"评分", "score"}};

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

    private JSONArray buildFiltersFor(String tid) throws Exception {
        String[][] subOpt;
        if ("1".equals(tid)) subOpt = SUB_MOVIE;
        else if ("2".equals(tid)) subOpt = SUB_TV;
        else if ("3".equals(tid)) subOpt = SUB_ZY;
        else subOpt = SUB_DM;

        JSONArray filters = new JSONArray();
        // typeid（子类）
        JSONArray subVal = new JSONArray();
        for (String[] kv : subOpt) subVal.put(new JSONObject().put("n", kv[1]).put("v", kv[0]));
        filters.put(new JSONObject().put("key", "typeid").put("name", "类型").put("value", subVal));

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

        // by
        JSONArray byVal = new JSONArray();
        byVal.put(new JSONObject().put("n", "最新").put("v", ""));
        for (String[] by : FILTER_BY) byVal.put(new JSONObject().put("n", by[0]).put("v", by[1]));
        filters.put(new JSONObject().put("key", "by").put("name", "排序").put("value", byVal));

        return filters;
    }

    // ==================== 分类 ====================
    //   无筛选: /vod-type-id/{tid}/pg/{P}.html
    //   有筛选: /vod-search-pg-{P}-wd--area-{A}-by-{B}-typeid-{T}-year-{Y}.html
    //           （段位按字母顺序，可任意组合；wd 为空表示"无搜索词的分类筛选"）

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Map<String, String> ext = extend == null ? new HashMap<String, String>() : extend;

        String typeid = seg(ext.get("typeid"));   // 子类 id（= 子类 vod-type-id 值）
        String area = seg(ext.get("area"));
        String year = seg(ext.get("year"));
        String by = seg(ext.get("by"));

        boolean hasFilter = area.length() > 0 || year.length() > 0 || by.length() > 0;
        // typeid = 当前分类的默认（子类"全部"的 v）= tid 时，不算筛选
        if (typeid.equals(tid)) typeid = "";

        String target;
        if (!hasFilter && typeid.length() == 0) {
            target = host + "/vod-type-id-" + tid + "-pg-" + page + ".html";
        } else {
            // 用搜索 URL 做筛选；wd 留空表示"无关键词"
            target = buildSearchUrl("", typeid, area, year, by, page);
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
    //   /vod-search-pg-{P}-wd-{urlEnc(wd)}[-area-...][-by-...][-typeid-...][-year-...].html
    //   段位按字母顺序，可任意组合

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parseInt(pg, 1);
        String target = buildSearchUrl(key == null ? "" : key.trim(), "", "", "", "", page);
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

    /** 构造搜索 URL（也用于分类筛选）。段位按字母顺序：area / by / typeid / wd / year */
    private String buildSearchUrl(String wd, String typeid, String area, String year, String by, int page) {
        String encWd = urlEncode(wd);
        StringBuilder sb = new StringBuilder(host).append("/vod-search-pg-").append(page).append("-wd-").append(encWd);
        if (area.length() > 0) sb.append("-area-").append(urlEncode(area));
        if (by.length() > 0) sb.append("-by-").append(by);
        if (typeid.length() > 0) sb.append("-typeid-").append(typeid);
        if (year.length() > 0) sb.append("-year-").append(year);
        sb.append(".html");
        return sb.toString();
    }

    // ==================== 详情 ====================
    //  thumb 链接: /{slug}/{yyyyMM}/{id}.html
    //  vod_id 用数字 {id}（与播放链接 /vod-play-id-{id}-src-S-num-T 一致）

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String raw = ids.get(0);
        // 兼容旧/新两种 id：纯数字就直接走 {id}.html；slug 形式则走完整 URL
        String url;
        if (raw.matches("\\d+")) {
            url = host + "/vod-detail-id-" + raw + ".html";
            // 新模板不再用 /vod/detail/id/... ，先尝试一次，失败后回落到首页 thumb 的常见路径 /{slug}/.../{id}.html 无法定位（不知道 slug）
            // 实际上 thumb 的 slug/year 都是路径的一部分，没有 slug 也无法直接打开详情页。
            // 这里直接以 {id}.html 的二级兜底页（若有），实际站点 id 不一定能解析 → 抛异常。
        } else if (raw.startsWith("http")) {
            url = raw;
        } else {
            // raw 形如 "/juqingpian/202609/276930.html"
            url = raw.startsWith("/") ? host + raw : host + "/" + raw;
        }
        String html = get(url);

        String name = first(html, "<h1>([^<]+)");
        if (name.length() == 0) name = first(html, "<h1[^>]*>([^<]+)");

        String pic = firstIgnoreCase(html, "data-original=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");
        if (pic.length() == 0) pic = firstIgnoreCase(html, "<img[^>]+src=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"");
        // 简介
        String content = clean(first(html, "<span class=\"detail-content\"[^>]*>([\\s\\S]*?)</span>"));
        if (content.length() == 0) content = clean(first(html, "<div class=\"detail\"[^>]*>([\\s\\S]*?)</div>"));
        if (content.length() == 0) content = clean(first(html, "<div[^>]*class=\"[^\"]*des[^\"]*\"[^>]*>([\\s\\S]*?)</div>"));
        // type/分类

        // 数据行（类型/地区/年份/主演/导演/更新）
        Map<String, String> data = new HashMap<String, String>();
        Matcher dm = Pattern.compile("<p class=\"data[^\"]*\"[^>]*>\\s*<span[^>]*>([^：:<]+)</span>([\\s\\S]*?)</p>").matcher(html);
        while (dm.find()) {
            String k = dm.group(1).trim();
            String v = clean(dm.group(2));
            if (k.length() > 0 && v.length() > 0 && !data.containsKey(k)) data.put(k, v);
        }

        // 播放源 & 分集（按 tab81..tab86 顺序）
        String id = raw;
        if (!id.matches("\\d+")) {
            // 从 url 里抽取 id
            Matcher em2 = Pattern.compile("/(\\d+)\\.html").matcher(raw);
            if (em2.find()) id = em2.group(1);
        }
        List<String> froms = new ArrayList<String>();
        List<String> urls = new ArrayList<String>();
        Matcher sm = Pattern.compile("<ul class=\"nav_tab[^\"]*\"[^>]*>([\\s\\S]*?)</ul>|<ul[^>]*class=\"[^\"]*tab[^\"]*\"[^>]*>([\\s\\S]*?)</ul>").matcher(html);
        // 先收集源名（顺序与 tab8X 一致）
        Matcher lm = Pattern.compile("<li[^>]*id=\"tab8(\\d)\"[^>]*>([\\s\\S]*?)</li>").matcher(html);
        Map<Integer, String> srcMap = new HashMap<Integer, String>();
        while (lm.find()) {
            int src = parseInt(lm.group(1), 0);
            String name2 = clean(lm.group(2));
            // 去 playerico
            if (name2.length() > 0) srcMap.put(src, name2);
        }

        Matcher pm = Pattern.compile("<div id=\"stab8(\\d)\"[^>]*>([\\s\\S]*?)</div>\\s*(?=<div id=\"stab8|<div id=\"stab1|$)").matcher(html);
        // 上面的尾部 lookahead 不可靠，改用更稳的方式：循环找出 stab8\d 块
        for (int src = 1; src <= 9; src++) {
            int sIdx = html.indexOf("id=\"stab8" + src + "\"");
            if (sIdx < 0) continue;
            int eIdx = html.indexOf("id=\"stab8", sIdx + 1);
            if (eIdx < 0) eIdx = html.length();
            else {
                // 找到下一个 stab 块的 <div 起点
                int divStart = html.lastIndexOf("<div", sIdx + 1);
                if (divStart > sIdx) eIdx = divStart;
            }
            String block = html.substring(sIdx, eIdx);
            Matcher em3 = Pattern.compile("<a href=\"(/vod-play-id-(\\d+)-src-(\\d+)-num-(\\d+)\\.html)\"[^>]*title=\"([^\"]*)\"[^>]*>").matcher(block);
            StringBuilder eps = new StringBuilder();
            int n = 0;
            while (em3.find()) {
                String label = em3.group(5).trim();
                if (label.length() == 0) label = "第" + (n + 1) + "集";
                if (n > 0) eps.append('#');
                eps.append(label).append('$').append(em3.group(1));
                n++;
            }
            if (n == 0) continue;
            String srcName = srcMap.get(src);
            if (srcName == null || srcName.length() == 0) srcName = "线路" + src;
            froms.add(srcName);
            urls.add(eps.toString());
        }

        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name.length() > 0 ? name : id);
        vod.put("vod_pic", fix(pic));
        vod.put("type_name", opt(data, "类型", ""));
        vod.put("vod_year", opt(data, "年份", ""));
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
    //  详情页构造的分集 URL 为 /vod-play-id-{N}-src-{S}-num-{T}.html
    //  播放页 HTML 内嵌 var mac_url=unescape('%uXXXX...$token$from')，
    //  站点 playerconfig.js (/js/playerconfig.js) + /player/{from}.js 二次跳转到 jsjinfu.com 等第三方解析站
    //  → 这里不做 JS 解码（mac_url 中的 base64 token 还经过了额外混淆），直接返回播放页 URL，
    //    让客户端（parse=1）用 WebView/fetch 自行解析 m3u8。

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String url = id;
        if (!url.startsWith("http")) {
            url = host + (url.startsWith("/") ? url : "/" + url);
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

    // ==================== 列表解析（统一处理 首页 / 分类 / 搜索） ====================
    //  列表项: <li class="p1 m1"><a href="/{slug}/{yyyyMM}/{id}.html" title="...">
    //             <img data-original="...">
    //             <span class="lzbz">
    //                 <p class="name">{name}</p>
    //                 <p class="actor">{actors}</p>
    //                 <p class="actor">{type}</p>
    //                 <p class="actor">{year/area}</p>
    //             </span>
    //             <p class="other"><i>{remarks}</i></p>
    //         </a></li>

    private JSONArray parseList(String html) throws Exception {
        JSONArray videos = new JSONArray();
        if (html == null || html.length() == 0) return videos;
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("<li class=\"p1 m1\">\\s*<a href=\"(/[^\"]+\\.html)\" title=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</a>\\s*</li>").matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String name = m.group(2).trim();
            String inner = m.group(3);
            if (name.length() == 0) continue;
            // 提取 id（路径末尾 /{id}.html 的数字）
            String id = "";
            Matcher idm = Pattern.compile("/(\\d+)\\.html$").matcher(href);
            if (idm.find()) id = idm.group(1);
            if (id.length() == 0 || !seen.add(id)) continue;

            String pic = first(inner, "data-original=\"([^\"]+)\"");
            if (pic.length() == 0) pic = first(inner, "<img[^>]+src=\"([^\"]+)\"");

            // 备注（更新状态）
            String remarks = first(inner, "<p class=\"other\">[\\s\\S]*?<i>([^<]+)</i>");
            if (remarks.length() == 0) remarks = first(inner, "<p class=\"other\">\\s*([^<\\s]+)");

            // 类型 / 年份-地区（从 lzbz 取）
            String typeName = "";
            String yearArea = "";
            Matcher lm = Pattern.compile("<span class=\"lzbz\"[^>]*>([\\s\\S]*?)</span>").matcher(inner);
            if (lm.find()) {
                List<String> actorLines = new ArrayList<String>();
                Matcher am = Pattern.compile("<p class=\"actor\"[^>]*>([^<]*)</p>").matcher(lm.group(1));
                while (am.find()) actorLines.add(am.group(1).trim());
                if (actorLines.size() >= 2) typeName = actorLines.get(1);
                if (actorLines.size() >= 3) yearArea = actorLines.get(2);
            }

            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", name);
            vod.put("vod_pic", fix(pic));
            vod.put("vod_remarks", remarks);
            if (typeName.length() > 0) vod.put("type_name", typeName);
            if (yearArea.length() > 0) {
                int yIdx = yearArea.indexOf('/');
                if (yIdx > 0) {
                    String y = yearArea.substring(0, yIdx).trim();
                    String a = yearArea.substring(yIdx + 1).trim();
                    if (y.matches("\\d{4}")) vod.put("vod_year", y);
                    vod.put("vod_area", a);
                }
            }
            videos.put(vod);
        }
        return videos;
    }

    private int parsePageCount(String html) {
        if (html == null || html.length() == 0) return 9999;
        Matcher m = Pattern.compile("当前\\s*[:：]\\s*\\d+\\s*/\\s*(\\d+)\\s*页").matcher(html);
        if (m.find()) return parseInt(m.group(1), 9999);
        Matcher m2 = Pattern.compile("/\\s*(\\d+)\\s*页<").matcher(html);
        if (m2.find()) return parseInt(m2.group(1), 9999);
        return 9999;
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

    private String urlEncode(String v) {
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