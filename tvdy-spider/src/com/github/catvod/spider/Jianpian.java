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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 荐片  https://api.ztcgi.com
 * <p>
 * 接口契约（自第三方 jar 中实测得到，已用浏览器 UA 直接复现）：
 * <pre>
 *   homeContent    硬编码 5 个分类：电影1 / 电视剧2 / 动漫3 / 综艺4 / 短剧67
 *                  筛选 1~4（area / year / sort）和 67（category_id）
 *   homeVideoContent  GET /api/dyTag/list?category_id=88         首页推荐（含短剧/电影混合 banner）
 *   categoryContent   tid==67 : GET /api/crumb/shortList?fcate_pid=67&category_id=&sort=update&page=
 *                  其他 : GET /api/crumb/list?fcate_pid={tid}&category_id=&area=&year=&type=&sort=&page=
 *                  （{area}/{year}/{sort} 由筛选 extend 提供；{category_id}/{type} 仅部分分类使用）
 *   detailContent   vod_id 形如  id$$$title$$$pic$$$tid   —— 与列表一致
 *                  tid!=67 : GET /api/video/detailv2?id={id}   data.source_list_source[*].source_list[*]
 *                  tid==67 : GET /api/detail?vid={id}          data.playlist[*]
 *   searchContent     GET /api/v2/search/videoV2?key={k}&category_id=88&page=1&pageSize=20
 *                  列表 vod_id 内嵌 top_category.id 以便点入详情走正确分支
 *   playerContent    非 http 直链 → 透传（parse=0），http(s) → 走外置解析（parse=1）
 *   init            拉一次 /api/v2/settings/resourceDomainConfig 缓存首个 img 域名到图片回填
 * </pre>
 */
public class Jianpian extends Spider {

    private static final String DEFAULT_HOST = "https://api.ztcgi.com";

    private static final String UA = "Mozilla/5.0 (Linux; Android 7.1.2; V2049A Build/UP1A.231005.007; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/81.0.4044.117 Mobile Safari/537.36;"
            + "webank/h5face;webank/1.0;netType:NETWORK_WIFI;appVersion=422;packageName=com.jp3.xg3";

    private static final String[][] CLASSES = {
            {"1", "电影"}, {"2", "电视剧"}, {"3", "动漫"}, {"4", "综艺"}, {"67", "短剧"}};

    // 短剧二级分类
    private static final String[][] SHORT_SUBS = {
            {"", ""}, {"言情", "70"}, {"爱情", "71"}, {"战神", "72"}, {"古代", "73"}, {"萌娃", "74"},
            {"神医", "75"}, {"玄幻", "76"}, {"重生", "77"}, {"激情", "79"}, {"时尚", "82"},
            {"剧情演绎", "83"}, {"影视", "84"}, {"人文社科", "85"}, {"二次元", "86"},
            {"明星八卦", "87"}, {"随拍", "88"}, {"个人管理", "89"}, {"音乐", "90"},
            {"汽车", "91"}, {"休闲", "92"}, {"校园教育", "93"}, {"游戏", "94"},
            {"科普", "95"}, {"科技", "96"}, {"时政社会", "97"}, {"萌宠", "98"},
            {"体育", "99"}, {"穿越", "80"}, {"闪婚", "112"}};

    private static final String[] AREAS  = {"", "国产:1", "中国香港:3", "中国台湾:6", "美国:5", "韩国:18", "日本:2"};
    private static final String[] YEARS  = {"", "2026:162", "2025:107", "2024:119", "2023:153", "2022:101", "2021:118",
            "2020:16", "2019:7", "2018:2", "2017:3", "2016:22"};
    private static final String[] SORTS  = {"热门:hot", "更新:update", "评分:rating"};

    private String host = DEFAULT_HOST;
    private String imgHost = "img.cdgbq.com";

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) throws Exception {
        try {
            String body = httpGet(host + "/api/v2/settings/resourceDomainConfig", null);
            if (body != null) {
                String dom = new JSONObject(body).getJSONObject("data").optString("imgDomain", "");
                int c = dom.indexOf(',');
                String first = c >= 0 ? dom.substring(0, c) : dom;
                if (first.trim().length() > 0) imgHost = first.trim();
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 首页 ====================

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray classes = new JSONArray();
        for (String[] c : CLASSES) classes.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        JSONObject result = new JSONObject().put("class", classes);
        if (filter) {
            JSONObject filters = new JSONObject();
            JSONArray area = kvArr(AREAS);
            JSONArray year = kvArr(YEARS);
            JSONArray sort = kvArr(SORTS);
            for (int i = 1; i <= 4; i++) {
                JSONArray f = new JSONArray();
                f.put(new JSONObject().put("key", "area").put("name", "地区").put("value", area));
                f.put(new JSONObject().put("key", "year").put("name", "年份").put("value", year));
                f.put(new JSONObject().put("key", "sort").put("name", "排序").put("value", sort));
                filters.put(String.valueOf(i), f);
            }
            JSONArray f67 = new JSONArray();
            JSONArray cat = new JSONArray();
            for (String[] kv : SHORT_SUBS) cat.put(new JSONObject().put("n", kv[0]).put("v", kv[1]));
            f67.put(new JSONObject().put("key", "category_id").put("name", "类型").put("value", cat));
            filters.put("67", f67);
            result.put("filters", filters);
        }
        return result.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        try {
            String body = httpGet(host + "/api/dyTag/list?category_id=88", null);
            if (body == null) return new JSONObject().put("list", new JSONArray()).toString();
            JSONArray data = new JSONObject(body).optJSONArray("data");
            JSONArray out = new JSONArray();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONArray inner = data.getJSONObject(i).optJSONArray("dataList");
                    if (inner == null) continue;
                    for (int j = 0; j < inner.length(); j++) {
                        JSONObject v = inner.getJSONObject(j);
                        String title = v.optString("title");
                        String pic = fixPic(v.optString("path"));
                        String remarks = v.optString("mask");
                        int id = v.optInt("id");
                        out.put(new JSONObject()
                                .put("vod_id", id + "$$$" + title + "$$$" + pic + "$$$1")
                                .put("vod_name", title)
                                .put("vod_pic", pic)
                                .put("vod_remarks", remarks));
                    }
                }
            }
            return new JSONObject().put("list", out).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 分类 ====================

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        String url;
        if ("67".equals(tid)) {
            String catId = extendGet(extend, "category_id", "");
            url = host + "/api/crumb/shortList?fcate_pid=67&category_id=" + catId + "&sort=update&page=" + page;
        } else {
            String area = extendGet(extend, "area", "");
            String year = extendGet(extend, "year", "");
            String sort = extendGet(extend, "sort", "update");
            String type = extendGet(extend, "type", "");
            String catId = extendGet(extend, "category_id", "");
            url = host + "/api/crumb/list?fcate_pid=" + tid + "&category_id=" + catId
                    + "&area=" + area + "&year=" + year + "&type=" + type + "&sort=" + sort + "&page=" + page;
        }
        try {
            String body = httpGet(url, null);
            if (body == null) return new JSONObject().put("list", new JSONArray()).toString();
            JSONArray data = new JSONObject(body).optJSONArray("data");
            JSONArray list = new JSONArray();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject v = data.getJSONObject(i);
                    String title = v.optString("title");
                    String pic = "67".equals(tid) ? fixPic(v.optString("cover_image")) : fixPic(v.optString("path"));
                    String remarks = v.optString("mask");
                    if (remarks.length() == 0) remarks = v.optString("score");
                    int id = v.optInt("id");
                    list.put(new JSONObject()
                            .put("vod_id", id + "$$$" + title + "$$$" + pic + "$$$" + tid)
                            .put("vod_name", title)
                            .put("vod_pic", pic)
                            .put("vod_remarks", remarks));
                }
            }
            return new JSONObject()
                    .put("list", list)
                    .put("page", page)
                    .put("pagecount", Integer.MAX_VALUE)
                    .put("limit", list.length())
                    .put("total", Integer.MAX_VALUE)
                    .toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 详情 ====================

    @Override
    public String detailContent(List<String> ids) throws Exception {
        try {
            String[] parts = ids.get(0).split("\\$\\$\\$");
            String id = parts[0];
            String title = parts[1];
            String pic = parts[2];
            String tid = parts[3];
            String url = "67".equals(tid) ? (host + "/api/detail?vid=" + id) : (host + "/api/video/detailv2?id=" + id);
            String body = httpGet(url, null);
            if (body == null) return new JSONObject().put("list", new JSONArray()).toString();
            JSONObject data = new JSONObject(body).getJSONObject("data");
            JSONObject vod = new JSONObject();
            vod.put("vod_id", ids.get(0));
            vod.put("vod_name", title);
            vod.put("vod_pic", pic);
            vod.put("vod_content", data.optString("description"));
            List<String> froms = new ArrayList<>();
            List<String> urls  = new ArrayList<>();
            if ("67".equals(tid)) {
                if (data.has("playlist") && data.get("playlist") instanceof JSONArray) {
                    JSONArray playlist = data.getJSONArray("playlist");
                    List<String> eps = new ArrayList<>();
                    for (int i = 0; i < playlist.length(); i++) {
                        JSONObject ep = playlist.getJSONObject(i);
                        String u = ep.optString("url");
                        if (u.length() > 0) eps.add(ep.optString("title") + "$" + u);
                    }
                    if (eps.size() > 0) {
                        froms.add("常规线路");
                        urls.add(join(eps, "#"));
                    }
                }
            } else {
                vod.put("vod_year", data.optString("year"));
                vod.put("vod_area", data.optString("area"));
                JSONArray actors = data.optJSONArray("actors");
                List<String> actList = new ArrayList<>();
                if (actors != null) for (int i = 0; i < actors.length(); i++) actList.add(actors.getJSONObject(i).optString("name"));
                vod.put("vod_actor", actList.toString());
                vod.put("vod_director", "");
                vod.put("vod_remarks", "");
                vod.put("type_name", "");
                if (data.has("source_list_source") && data.get("source_list_source") instanceof JSONArray) {
                    JSONArray sources = data.getJSONArray("source_list_source");
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject src = sources.getJSONObject(i);
                        String fromName = src.optString("name");
                        JSONArray sl = src.optJSONArray("source_list");
                        if (sl == null) continue;
                        List<String> eps = new ArrayList<>();
                        for (int j = 0; j < sl.length(); j++) {
                            JSONObject e = sl.getJSONObject(j);
                            String u = e.optString("url");
                            if (u.length() == 0) continue;
                            if (u.startsWith("ftp")) u = "tvbox-xg:" + u;
                            eps.add(e.optString("source_name") + "$" + u);
                        }
                        if (eps.size() > 0) {
                            froms.add(fromName);
                            urls.add(join(eps, "#"));
                        }
                    }
                }
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
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        try {
            String url = host + "/api/v2/search/videoV2?key=" + URLEncoder.encode(key, "UTF-8")
                    + "&category_id=88&page=" + pg + "&pageSize=20";
            String body = httpGet(url, null);
            if (body == null) return new JSONObject().put("list", new JSONArray()).toString();
            JSONArray data = new JSONObject(body).optJSONArray("data");
            JSONArray list = new JSONArray();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject v = data.getJSONObject(i);
                    String title = v.optString("title");
                    String pic = fixPic(v.optString("thumbnail"));
                    int id = v.optInt("id");
                    int topCat = v.getJSONObject("top_category").optInt("id");
                    list.put(new JSONObject()
                            .put("vod_id", id + "$$$" + title + "$$$" + pic + "$$$" + topCat)
                            .put("vod_name", title)
                            .put("vod_pic", pic)
                            .put("vod_remarks", v.optString("mask")));
                }
            }
            return new JSONObject().put("list", list).toString();
        } catch (Throwable t) {
            return new JSONObject().put("list", new JSONArray()).toString();
        }
    }

    // ==================== 播放 ====================

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        try {
            JSONObject o = new JSONObject();
            if (id != null && id.startsWith("http")) {
                o.put("parse", 1).put("jx", "1").put("url", id);
            } else {
                o.put("parse", 0).put("url", id == null ? "" : id);
            }
            return o.toString();
        } catch (Throwable t) {
            return "{\"parse\":0,\"url\":\"\"}";
        }
    }

    // ==================== 工具方法 ====================

    private static String httpGet(String url, Map<String, String> extraHeaders) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            if (extraHeaders != null) for (Map.Entry<String, String> e : extraHeaders.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static JSONArray kvArr(String[] kvs) {
        JSONArray a = new JSONArray();
        for (String s : kvs) {
            int c = s.indexOf(':');
            String n = c >= 0 ? s.substring(0, c) : s;
            String v = c >= 0 ? s.substring(c + 1) : "";
            a.put(new JSONObject().put("n", n).put("v", v));
        }
        return a;
    }

    private static String extendGet(HashMap<String, String> extend, String key, String def) {
        if (extend == null) return def;
        String v = extend.get(key);
        return v == null || v.length() == 0 ? def : v;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }

    private String fixPic(String path) {
        if (path == null || path.length() == 0) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (path.startsWith("/")) return "https://" + imgHost + path;
        return "https://" + rand(6) + "." + path;
    }

    private static final Random RAND = new Random();

    private static String rand(int n) {
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = "abcdefghijklmnopqrstuvwxyz0123456789".charAt(RAND.nextInt(36));
        return new String(c);
    }
}