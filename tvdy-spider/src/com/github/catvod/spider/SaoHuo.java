package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 骚火电影（shdy2.com / shdy3.com / shdy5.us，发布页 shapp.us）
 * <p>
 * 模板：自制模板（v_img 列表 + play_link 播放列表 + iframe 播放器 + /api.php 解析）。
 * <pre>
 *   发布页     http://shapp.us  → .content-top ul li a 依次探测取第一个可用域名
 *   首页       /                       .top_bar.clearfix a[href*=list] 为分类导航
 *   列表       /list/{tid}-{pg}.html    .grid_box ul li → a@href/a@title/img@data-original/.v_note
 *   搜索       /search.php?searchword={kw}&limit=500   （一次返回全部）
 *   详情       /movie/{id}.html         .v_info_box p 备注 / .p_txt.show_part 剧情 /
 *                                      .play_from ul li 线路名 / ul.play_list li 每线路剧集(倒序)
 *   播放       分集页 → section iframe src → 播放器页取 var url/t/key(hhh混淆)/act/play
 *              → POST {播放器origin}/api.php (form) → {url: 直链m3u8}
 *   兜底       解析失败则 parse=1 交给嗅探
 * </pre>
 * ext 支持 `{"host":"https://shdy2.com"}`、裸域名或完整 URL 覆盖（默认走 shapp.us 自动发现）。
 */
public class SaoHuo extends Spider {

    private static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String PUBLISH_PAGE = "http://shapp.us";
    private static final String[] FALLBACK_HOSTS = {
            "https://shdy2.com", "https://shdy3.com", "https://shdy5.us", "https://saohuo.vip"};

    /** 兜底分类（top_bar 解析失败时使用；tid 取自官方 XB 规则） */
    private static final String[][] DEFAULT_CLASSES = {
            {"1", "电影"}, {"2", "电视剧"}, {"4", "动漫"}, {"46", "亲子动漫"}};

    /** 筛选：与模板子分类 tid 对应（1=电影 2=电视剧） */
    private static final Object[][] FILTERS = {
            {"1", new String[][]{{"6", "喜剧"}, {"7", "爱情"}, {"8", "恐怖"}, {"9", "动作"},
                    {"10", "科幻"}, {"11", "战争"}, {"12", "犯罪"}, {"13", "动画"}, {"14", "奇幻"},
                    {"15", "剧情"}, {"16", "冒险"}, {"17", "悬疑"}, {"18", "惊悚"}, {"19", "其它"}}},
            {"2", new String[][]{{"20", "大陆剧"}, {"21", "港剧"}, {"22", "韩剧"}, {"23", "美剧"},
                    {"24", "日剧"}, {"25", "英剧"}, {"26", "台剧"}, {"27", "其它"}}}};

    private String host = "";

    @Override
    public void init(Context context, String extend) throws Exception {
        try {
            if (extend != null) {
                String e = extend.trim();
                if (e.startsWith("{")) {
                    e = new JSONObject(e).optString("host", "");
                }
                if (e.startsWith("http")) {
                    host = stripTail(e);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        host = discoverHost();
    }

    private static String stripTail(String h) {
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** 发布页发现域名：content-top 里第一个可打开的链接 */
    private String discoverHost() {
        try {
            String page = httpGet(PUBLISH_PAGE, null);
            Matcher m = Pattern.compile("href=\"(https?://[^\"]+)\"").matcher(page);
            List<String> candidates = new ArrayList<>();
            while (m.find()) {
                String h = stripTail(m.group(1));
                if (h.contains("shdy") || h.contains("saohuo") || h.contains("shapp")) {
                    if (h.contains("shapp")) continue;
                    if (!candidates.contains(h)) candidates.add(h);
                }
            }
            for (String h : candidates) {
                try {
                    String body = httpGet(h + "/", header());
                    if (body.contains("骚火") || body.contains("v_img") || body.contains("grid_box")) return h;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        for (String h : FALLBACK_HOSTS) {
            try {
                String body = httpGet(h + "/", header());
                if (body.contains("骚火") || body.contains("v_img") || body.contains("grid_box")) return h;
            } catch (Throwable ignored) {
            }
        }
        return FALLBACK_HOSTS[0];
    }

    private Map<String, String> header() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        if (host.length() > 0) h.put("Referer", host + "/");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return h;
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        String html = httpGet(host + "/", header());
        JSONArray classes = new JSONArray();
        Matcher m = Pattern.compile("<a[^>]*href=\"[^\"]*/list/(\\d+)[^\"]*\"[^>]*>([^<]{1,10})</a>").matcher(html);
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        while (m.find()) {
            String id = m.group(1), name = m.group(2).trim();
            if (name.length() == 0 || "首页".equals(name)) continue;
            seen.put(id, name);
        }
        if (seen.isEmpty()) {
            for (String[] c : DEFAULT_CLASSES) seen.put(c[0], c[1]);
        }
        for (Map.Entry<String, String> e : seen.entrySet()) {
            classes.put(new JSONObject().put("type_id", e.getKey()).put("type_name", e.getValue()));
        }
        JSONObject result = new JSONObject().put("class", classes);
        if (filter) {
            JSONObject filters = new JSONObject();
            for (Object[] f : FILTERS) {
                String catId = (String) f[0];
                String[][] opts = (String[][]) f[1];
                if (!seen.containsKey(catId)) continue;
                JSONArray vals = new JSONArray();
                vals.put(new JSONObject().put("n", "全部").put("v", ""));
                for (String[] o : opts) vals.put(new JSONObject().put("n", o[1]).put("v", o[0]));
                filters.put(catId, new JSONArray().put(
                        new JSONObject().put("key", "tid").put("name", "类型").put("value", vals)));
            }
            result.put("filters", filters);
        }
        result.put("list", parseList(html));
        return result.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        try {
            return new JSONObject().put("list", parseList(httpGet(host + "/", header()))).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 列表 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try { page = Integer.parseInt(pg); } catch (Throwable ignored) { }
        if (page < 1) page = 1;
        String realTid = tid;
        if (extend != null && extend.containsKey("tid") && extend.get("tid").length() > 0) realTid = extend.get("tid");
        JSONArray list;
        int pagecount = page;
        try {
            String html = httpGet(host + "/list/" + realTid + "-" + page + ".html", header());
            list = parseList(html);
            // 有下一页链接则允许继续翻
            if (Pattern.compile("(下一页|next)", Pattern.CASE_INSENSITIVE).matcher(html).find()) pagecount = page + 1;
        } catch (Throwable t) {
            list = new JSONArray();
        }
        return new JSONObject()
                .put("list", list)
                .put("page", page)
                .put("pagecount", pagecount)
                .put("limit", "90")
                .put("total", list.length() > 0 ? 999999 : 0)
                .toString();
    }

    /** 解析 grid_box 列表项：href / title / data-original / v_note */
    private JSONArray parseList(String html) throws Exception {
        JSONArray list = new JSONArray();
        if (html == null || html.length() == 0) return list;
        // 按 <li 切块，块内提取四个字段（对属性顺序不敏感）
        String[] blocks = html.split("<li");
        for (String b : blocks) {
            if (!b.contains("v_img") && !b.contains("/movie/")) continue;
            JSONObject v = parseItemBlock(b);
            if (v != null) list.put(v);
            if (list.length() >= 200) break;
        }
        if (list.length() == 0 && html.contains("v_img")) {
            // 搜索页可能不用 li 结构，按 v_img 块切
            for (String b : html.split("class=\"v_img\"")) {
                JSONObject v = parseItemBlock(b);
                if (v != null) list.put(v);
                if (list.length() >= 200) break;
            }
        }
        return list;
    }

    private JSONObject parseItemBlock(String b) throws Exception {
        String href = firstGroup(b, "href=\"([^\"]*/movie/[^\"]+\\.html)\"");
        if (href.length() == 0) return null;
        String title = firstGroup(b, "title=\"([^\"]+)\"");
        String pic = firstGroup(b, "data-original=\"([^\"]+)\"");
        if (pic.length() == 0) pic = firstGroup(b, "src=\"(http[^\"]+\\.(?:jpg|png|webp)[^\"]*)\"");
        String note = stripTags(firstGroup(b, "class=\"v_note\"[^>]*>([\\s\\S]{0,60}?)<"));
        if (title.length() == 0) title = href;
        return new JSONObject()
                .put("vod_id", href)
                .put("vod_name", title)
                .put("vod_pic", pic)
                .put("vod_remarks", note);
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        try {
            String html = httpGet(host + (id.startsWith("/") ? id : "/" + id), header());
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);

            String name = firstGroup(html, "<h1[^>]*>([^<]+)</h1>");
            if (name.length() == 0) name = firstGroup(html, "<title>([^<-]+)");
            vod.put("vod_name", name.trim());
            String remarks = stripTags(firstGroup(html, "class=\"v_info_box[\\s\\S]{0,300}?<p[^>]*>([\\s\\S]{0,80}?)</p>"));
            if (remarks.length() == 0) remarks = stripTags(firstGroup(html, "class=\"v_note\"[^>]*>([\\s\\S]{0,60}?)</div>"));
            vod.put("vod_remarks", remarks);
            String content = firstGroup(html, "class=\"p_txt show_part\"[^>]*>([\\s\\S]*?)<br");
            vod.put("vod_content", stripTags(content));

            String director = firstGroup(html, "导演[：:]\\s*([\\s\\S]{1,100}?)\\s*主演");
            if (director.length() == 0) director = firstGroup(html, "导演[：:]\\s*([\\s\\S]{1,100}?)</");
            vod.put("vod_director", stripTags(director));
            String actor = firstGroup(html, "主演[：:]\\s*([\\s\\S]{1,400}?)</p>");
            vod.put("vod_actor", stripTags(actor));

            // 线路名（play_from 区内所有 li 文本）
            List<String> froms = new ArrayList<>();
            String fromSection = firstGroup(html, "(class=\"play_from[\\s\\S]*?)(?=play_list|play_link|</div>)");
            if (fromSection.length() == 0) fromSection = firstGroup(html, "(class=\"play_form[\\s\\S]*?)(?=play_list|play_link|</div>)");
            Matcher li = Pattern.compile("<li[^>]*>([^<]*)</li>").matcher(fromSection);
            while (li.find()) if (li.group(1).trim().length() > 0) froms.add(li.group(1).trim());
            // 各线路剧集（ul.play_list，每条对应一个线路，集数倒序）
            List<String> urls = new ArrayList<>();
            Matcher um = Pattern.compile("<ul[^>]*(?:class=\"play_list|id=\"play_link)[^>]*>([\\s\\S]*?)</ul>").matcher(html);
            while (um.find()) {
                List<String[]> eps = new ArrayList<>();
                Matcher am = Pattern.compile("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(um.group(1));
                while (am.find()) eps.add(new String[]{am.group(2).trim(), am.group(1)});
                if (eps.isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                for (int i = eps.size() - 1; i >= 0; i--) { // 倒序还原（模板按最新在前渲染）
                    if (sb.length() > 0) sb.append("#");
                    sb.append(eps.get(i)[0]).append("$").append(eps.get(i)[1]);
                }
                urls.add(sb.toString());
            }
            if (froms.isEmpty()) {
                for (int i = 0; i < urls.size(); i++) froms.add("骚火" + (urls.size() > 1 ? String.valueOf(i + 1) : ""));
            } else if (froms.size() < urls.size()) {
                while (froms.size() < urls.size()) froms.add("线路" + (froms.size() + 1));
            }
            vod.put("vod_play_from", join(froms, "$$$"));
            vod.put("vod_play_url", join(urls, "$$$"));

            JSONArray list = new JSONArray();
            list.put(vod);
            return new JSONObject().put("list", list).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 搜索 ====================

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        try {
            String html = httpGet(host + "/search.php?searchword=" + urlEncode(key) + "&limit=500", header());
            return new JSONObject().put("list", parseList(html)).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String playUrl = host + (id.startsWith("/") ? id : "/" + id);
            String html = httpGet(playUrl, header());
            // 优先取 section[style*=padding-top] 内的播放器 iframe，回退第一个 iframe
            String iframe = firstGroup(html, "section[^>]*style=\"[^\"]*padding-top[^\"]*\"[^>]*>[\\s\\S]{0,600}?<iframe[^>]+src=\"([^\"]+)\"");
            if (iframe.length() == 0) iframe = firstGroup(html, "<iframe[^>]+src=\"([^\"]+)\"");
            if (iframe.length() > 0) {
                String surl = iframe.startsWith("http") ? iframe
                        : host + (iframe.startsWith("/") ? iframe : "/" + iframe);
                String playerHtml = httpGet(surl, playerHeader(surl));
                String url = firstGroup(playerHtml, "var\\s+url\\s*=\\s*\"([^\"]+)\"");
                String t = firstGroup(playerHtml, "var\\s+t\\s*=\\s*\"([^\"]+)\"");
                String keyEnc = firstGroup(playerHtml, "var\\s+key\\s*=\\s*hhh\\(\"([^\"]+)\"\\)");
                String act = firstGroup(playerHtml, "var\\s+act\\s*=\\s*\"([^\"]+)\"");
                String play = firstGroup(playerHtml, "var\\s+play\\s*=\\s*\"([^\"]+)\"");
                if (url.length() > 0 && keyEnc.length() > 0) {
                    String key = hhh(keyEnc);
                    String origin = surl.replaceAll("(https?://[^/]+).*", "$1");
                    String body = "url=" + urlEncode(url)
                            + "&t=" + urlEncode(t)
                            + "&key=" + urlEncode(key)
                            + "&act=" + urlEncode(act)
                            + "&play=" + urlEncode(play);
                    String resp = httpPost(origin + "/api.php", body, playerHeader(surl));
                    String video = firstGroup(resp, "\"url\"\\s*:\\s*\"([^\"]+)\"");
                    if (video.length() == 0) video = firstGroup(resp, "'url'\\s*:\\s*'([^']+)'");
                    if (video.length() > 0 && !video.startsWith("/movie/")) {
                        JSONObject hd = new JSONObject();
                        hd.put("User-Agent", UA);
                        hd.put("Referer", host + "/");
                        return o.put("parse", 0).put("playUrl", "").put("url", video)
                                .put("header", hd).toString();
                    }
                }
            }
            // 兜底：网页嗅探
            return o.put("parse", 1).put("playUrl", "").put("url", playUrl)
                    .put("header", new JSONObject().put("User-Agent", UA)).toString();
        } catch (Throwable t) {
            return o.put("parse", 1).put("playUrl", "").put("url",
                    host + (id.startsWith("/") ? id : "/" + id)).toString();
        }
    }

    private Map<String, String> playerHeader(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer);
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("X-Requested-With", "XMLHttpRequest");
        return h;
    }

    /** 播放器 key 解混淆：base64 → 模板混淆 token → 字符 */
    private static String hhh(String enc) {
        String[][] tokens = {
                {"0Oo0o0O0", "a"}, {"1O0bO001", "b"}, {"2OoCcO2", "c"}, {"3O0dO0O3", "d"},
                {"4OoEeO4", "e"}, {"5O0fO0O5", "f"}, {"6OoGgO6", "g"}, {"7O0hO0O7", "h"},
                {"8OoIiO8", "i"}, {"9O0jO0O9", "j"}, {"0OoKkO0", "k"}, {"1O0lO0O1", "l"},
                {"2OoMmO2", "m"}, {"3O0nO0O3", "n"}, {"4OoOoO4", "o"}, {"5O0pO0O5", "p"},
                {"6OoQqO6", "q"}, {"7O0rO0O7", "r"}, {"8OoSsO8", "s"}, {"9O0tO0O9", "t"},
                {"0OoUuO0", "u"}, {"1O0vO0O1", "v"}, {"2OoWwO2", "w"}, {"3O0xO0O3", "x"},
                {"4OoYyO4", "y"}, {"5O0zO0O5", "z"}, {"0OoAAO0", "A"}, {"1O0BBO1", "B"},
                {"2OoCCO2", "C"}, {"3O0DDO3", "D"}, {"4OoEEO4", "E"}, {"5O0FFO5", "F"},
                {"6OoGGO6", "G"}, {"7O0HHO7", "H"}, {"8OoIIO8", "I"}, {"9O0JJO9", "J"},
                {"0OoKKO0", "K"}, {"1O0LLO1", "L"}, {"2OoMMO2", "M"}, {"3O0NNO3", "N"},
                {"4OoOOO4", "O"}, {"5O0PPO5", "P"}, {"6OoQQO6", "Q"}, {"7O0RRO7", "R"},
                {"8OoSSO8", "S"}, {"9O0TTO9", "T"}, {"0OoUO0", "U"}, {"1O0VVO1", "V"},
                {"2OoWWO2", "W"}, {"3O0XXO3", "X"}, {"4OoYYO4", "Y"}, {"5O0ZZO5", "Z"},
        };
        String decoded = b64Decode(enc);
        StringBuilder out = new StringBuilder();
        outer:
        for (int i = 0; i < decoded.length(); ) {
            for (String[] tk : tokens) {
                if (decoded.startsWith(tk[0], i)) {
                    out.append(tk[1]);
                    i += tk[0].length();
                    continue outer;
                }
            }
            out.append(decoded.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** 标准 base64 解码（容忍缺 padding 与空白） */
    private static String b64Decode(String s) {
        String t = s.replaceAll("[^A-Za-z0-9+/=]", "");
        int pad = (4 - t.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < pad; i++) sb.append('=');
        int[] rev = new int[128];
        Arrays.fill(rev, -1);
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alpha.length(); i++) rev[alpha.charAt(i)] = i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '=') break;
            int v = c < 128 ? rev[c] : -1;
            if (v < 0) continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        try {
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return out.toString();
        }
    }

    // ==================== HTTP ====================

    private String httpGet(String url, Map<String, String> headers) throws Exception {
        Throwable last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", UA);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                int status = conn.getResponseCode();
                InputStream in = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
                byte[] raw = in == null ? new byte[0] : readAll(in);
                if (status < 200 || status >= 400) throw new RuntimeException("HTTP " + status);
                return new String(raw, "UTF-8");
            } catch (Throwable t) {
                last = t;
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(400); } catch (Throwable ignored) { }
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new RuntimeException(last);
    }

    private String httpPost(String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null && !"Content-Type".equals(e.getKey())) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            String origin = url.replaceAll("(https?://[^/]+).*", "$1");
            conn.setRequestProperty("Origin", origin);
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();
            InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
            return new String(in == null ? new byte[0] : readAll(in), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ==================== 工具 ====================

    private static String firstGroup(String text, String regex) {
        if (text == null) return "";
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) return m.group(1).trim();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&").replaceAll("&quot;", "\"")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
