import com.github.catvod.spider.A123tv;
import com.github.catvod.spider.GuaZi;
import com.github.catvod.spider.Kky;
import com.github.catvod.spider.Vv3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** 本地联调用测试台（不参与打包，只用于验证 Spider 逻辑） */
public class TestSpider {

    static String cut(String s) {
        if (s == null) return "null";
        return s.length() > 1600 ? s.substring(0, 1600) + "..." : s;
    }

    static void log(String tag, Object v) {
        System.out.println("===== " + tag + " =====");
        System.out.println(cut(String.valueOf(v)));
        System.out.println();
    }

    /** 简单 GET，返回响应体文本（跟随重定向），用于模拟播放器取流 */
    static String fetch(String url, String ua, String referer) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (referer != null) c.setRequestProperty("Referer", referer);
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            int code = c.getResponseCode();
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            System.out.println("       (HTTP " + code + " ← " + c.getURL() + ")");
            c.disconnect();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            System.out.println("       (请求失败: " + e + ")");
            return "";
        }
    }

    static double duration(String m3u8) {
        double t = 0;
        for (String line : m3u8.split("\n")) {
            if (line.startsWith("#EXTINF:")) t += Double.parseDouble(line.substring(8).split(",")[0].trim());
        }
        return t;
    }

    static int segCount(String m3u8) {
        int n = 0;
        for (String line : m3u8.split("\n")) if (line.startsWith("#EXTINF:")) n++;
        return n;
    }

    /** 主播放列表里第一条变体流地址（m3u8 可能有多层）；已是分片列表时返回 "" */
    static String variantOf(String master, String srcUrl) {
        for (String line : master.split("\n")) {
            String t = line.trim();
            if (t.length() == 0 || t.charAt(0) == '#') continue;
            if (t.startsWith("http")) return t;
            try {
                java.net.URL u = new java.net.URL(srcUrl);
                String origin = u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
                if (t.startsWith("/")) return origin + t;
                return srcUrl.substring(0, srcUrl.lastIndexOf('/') + 1) + t;
            } catch (Throwable e) {
                return "";
            }
        }
        return "";
    }

    /** 取 m3u8 片长：自动钻透主播放列表 */
    static double playListDuration(String url, String ua) {
        String body = fetch(url, ua, null);
        if (segCount(body) > 0) return duration(body);
        String v = variantOf(body, url);
        if (v.length() == 0) return 0;
        String body2 = fetch(v, ua, null);
        double d = duration(body2);
        return d > 0 ? d : duration(body);
    }

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "vv3";

        if (which.equals("a123tv")) {
            final String HOST = "https://a123tv.com";
            final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
            A123tv s = new A123tv();
            s.init(null, args.length > 2 ? args[2] : "");

            long t0 = System.currentTimeMillis();
            String home = s.homeContent(true);
            System.out.println(">>> homeContent（纯静态，无网络）耗时 " + (System.currentTimeMillis() - t0) + "ms");
            log("HOME", home);
            log("HOME-VIDEO", s.homeVideoContent());

            // 筛选：电影/剧情片（子类 id 1006）
            HashMap<String, String> ext = new HashMap<String, String>();
            ext.put("class", "1006");
            String c1 = s.categoryContent("10", "1", true, ext);
            JSONObject c1o = new JSONObject(c1);
            System.out.println(">>> 筛选分类 条数=" + c1o.optJSONArray("list").length()
                    + " pagecount=" + c1o.optInt("pagecount"));
            log("CAT-10-filter(剧情片)-p1", c1);
            String c2 = s.categoryContent("10", "2", false, new HashMap<String, String>());
            System.out.println(">>> 无筛选第2页 条数=" + new JSONObject(c2).optJSONArray("list").length()
                    + " pagecount=" + new JSONObject(c2).optInt("pagecount"));
            log("CAT-13(动漫)-p1", s.categoryContent("13", "1", false, new HashMap<String, String>()));

            // 搜索 + 搜索翻页
            String s1 = s.searchContent("火影", false);
            System.out.println(">>> 搜索「火影」条数=" + new JSONObject(s1).optJSONArray("list").length());
            log("SEARCH 火影-p1", s1);
            String s2 = s.searchContent("火影", false, "2");
            System.out.println(">>> 搜索「火影」第2页 条数=" + new JSONObject(s2).optJSONArray("list").length());
            log("SEARCH 火影-p2", s2);

            // 详情 + 播放：电影（共N集含多语言）与连续剧（多集）各测一个
            JSONArray list = c1o.optJSONArray("list");
            String movie = args.length > 1 ? args[1] : list.getJSONObject(0).optString("vod_id");
            String[] targets = {movie, "guaiwu14"};
            for (String id : targets) {
                if (id == null || id.length() == 0) continue;
                System.out.println("\n########## DETAIL " + id + " ##########");
                String d = s.detailContent(java.util.Collections.singletonList(id));
                log("DETAIL " + id, d);
                JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
                System.out.println(">>> vod_name=" + vod.optString("vod_name")
                        + " | type=" + vod.optString("type_name")
                        + " | year=" + vod.optString("vod_year")
                        + " | remarks=" + vod.optString("vod_remarks")
                        + " | pic=" + vod.optString("vod_pic"));
                String from = vod.optString("vod_play_from");
                String playUrl = vod.optString("vod_play_url");
                String[] froms = from.split("\\$\\$\\$");
                String[] blocks = playUrl.split("\\$\\$\\$");
                System.out.println(">>> 线路数=" + froms.length + "，vod_play_url 长度=" + playUrl.length() + " 字节");
                for (int i = 0; i < froms.length && i < 4; i++) {
                    System.out.println("      线路[" + i + "] " + froms[i] + " → "
                            + blocks[i].split("#").length + " 集，首集="
                            + blocks[i].split("#")[0]);
                }
                if (blocks.length > 0) {
                    // 首集：走 spider 的 playerContent → m3u8
                    String firstEp = blocks[0].split("#")[0];
                    String epId = firstEp.substring(firstEp.indexOf('$') + 1);
                    String pj = s.playerContent(froms[0], epId, new ArrayList<String>());
                    log("PLAYER " + epId, pj);
                    JSONObject pr = new JSONObject(pj);
                    String prefix = pr.optString("playUrl", "");
                    String addr = pr.optString("url", "");
                    Object hobj = pr.opt("header");
                    String ua = hobj instanceof String ? new JSONObject((String) hobj).optString("User-Agent", "") : "";
                    String pu = prefix + addr;
                    System.out.println(">>> header " + (hobj instanceof String ? "String ✅" : "❌ 客户端会忽略")
                            + "，UA=" + (ua.contains("Mozilla") ? "浏览器 ✅ 与站点一致" : ua));
                    System.out.println(">>> playUrl 前缀=[" + prefix + "]"
                            + (prefix.length() == 0 ? " ✅ 留空" : " ❌ 会让客户端拼重地址")
                            + "，客户端实际播放 = " + pu);
                    double total = playListDuration(pu, ua);
                    System.out.println(">>> 播放列表片长 " + (int) total + "s"
                            + (total < 120 ? "  ❌ 疑似占位片/取流失败" : "  ✅ 正片"));

                    // 最后一集：校验「共N集」生成的下标确实有效（越界会静默回落到第01集，必须比对集名）
                    String lastEp = blocks[0];
                    String tail = lastEp.substring(lastEp.lastIndexOf('#') + 1);
                    String lastId = tail.substring(tail.indexOf('$') + 1);
                    String expect = tail.substring(0, tail.indexOf('$'));
                    String lastHtml = fetch(HOST + lastId, UA, HOST + "/");
                    String got = "";
                    java.util.regex.Matcher tm = java.util.regex.Pattern
                            .compile("data-title=\"([^\"]*)\"").matcher(lastHtml);
                    if (tm.find()) got = tm.group(1);
                    // 集名归一（第01集 == 第1集），越界时站点会静默回落到第01集
                    String e1 = expect.replaceAll("第0*(\\d+)", "第$1");
                    String e2 = got.replaceAll("第0*(\\d+)", "第$1");
                    System.out.println(">>> 末集 " + lastId + " 期望名=[" + expect + "] 站点返回 data-title=[" + got + "]"
                            + (e2.contains(e1) ? "  ✅ 未越界" : "  ❌ 越界被回落，集数解析有误"));
                }
            }
            return;
        }

        if (which.equals("guazi")) {
            GuaZi s = new GuaZi();
            // args[2] 可传 ext（如 {"proxy":true}）以验证本机中继兜底分支
            s.init(null, args.length > 2 ? args[2] : "");
            long t0 = System.currentTimeMillis();
            String home = s.homeContent(true);
            long t1 = System.currentTimeMillis();
            System.out.println(">>> homeContent 冷启动耗时 " + (t1 - t0) + "ms");
            String hv = s.homeVideoContent();
            System.out.println(">>> homeVideoContent 耗时 " + (System.currentTimeMillis() - t1) + "ms");
            long t2 = System.currentTimeMillis();
            s.homeContent(true);
            System.out.println(">>> homeContent 热缓存耗时 " + (System.currentTimeMillis() - t2) + "ms");
            log("HOME", home);
            log("HOME-VIDEO", hv);
            String c1 = s.categoryContent("1", "1", false, new HashMap<String, String>());
            log("CAT-1-p1", c1);
            log("CAT-1-p2", s.categoryContent("1", "2", false, new HashMap<String, String>()));
            HashMap<String, String> ext = new HashMap<String, String>();
            ext.put("area", "日本");
            ext.put("year", "2024");
            ext.put("sort", "d_score");
            log("CAT-1-filter(日本/2024/最热)", s.categoryContent("1", "1", true, ext));

            JSONArray list = new JSONObject(c1).optJSONArray("list");
            System.out.println("列表条数=" + list.length());
            String id = args.length > 1 ? args[1] : list.getJSONObject(0).optString("vod_id");
            String d = s.detailContent(java.util.Collections.singletonList(id));
            log("DETAIL " + id, d);

            JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
            System.out.println("vod_name=" + vod.optString("vod_name")
                    + " | area=" + vod.optString("vod_area")
                    + " | year=" + vod.optString("vod_year")
                    + " | score=" + vod.optString("vod_score")
                    + " | remarks=" + vod.optString("vod_remarks")
                    + " | actor=" + vod.optString("vod_actor")
                    + " | director=" + vod.optString("vod_director"));
            String from = vod.optString("vod_play_from");
            String playUrl = vod.optString("vod_play_url");
            System.out.println("from=" + from);
            System.out.println("play_url[0]=" + cut(playUrl));
            if (playUrl.length() > 0) {
                String block = playUrl.split("\\$\\$\\$")[0];
                String firstEp = block.split("#")[0];
                String epId = firstEp.substring(firstEp.indexOf('$') + 1);
                String pj = s.playerContent("瓜子", epId, new ArrayList<String>());
                log("PLAYER " + epId, pj);
                // 回归点 1（血泪教训）：playUrl 是「前缀」不是地址！客户端播的是 playUrl + url。
                // 曾经两个字段都塞完整地址 → 地址被拼重 → 客户端报「源视频文件丢失」/「未知错误」。
                JSONObject pr = new JSONObject(pj);
                String prefix = pr.optString("playUrl", "");
                String addr = pr.optString("url", "");
                Object hobj = pr.opt("header");
                String ua = hobj instanceof String ? new JSONObject((String) hobj).optString("User-Agent", "") : "";
                String pu = prefix + addr;      // 与客户端（PlayFragment: playUrl + url）完全一致的拼法
                System.out.println(">>> header " + (hobj instanceof String ? "String ✅" : "❌ 客户端会忽略")
                        + "，UA=[" + ua + "]" + (ua.contains("Mozilla") ? " ⚠️ 浏览器 UA" : " ✅ 非浏览器 UA"));
                System.out.println(">>> playUrl 前缀=[" + prefix + "]"
                        + (prefix.length() == 0 ? " ✅ 留空" : " ❌ 会让客户端拼重地址")
                        + "，客户端实际播放 = " + pu);
                final String browserUa = "Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
                // 回归点 2：按客户端画像（spider 下发的 UA、不带 Referer）取流 → 必须是正片
                String m3u8 = fetch(pu, ua, null);
                int segs = 0;
                String seg = null;
                for (String line : m3u8.split("\n")) {
                    if (line.startsWith("#EXTINF:")) segs++;
                    String t = line.trim();
                    if (seg == null && t.length() > 0 && t.charAt(0) != '#') seg = t;
                }
                double total = duration(m3u8);
                System.out.println(">>> 播放列表（客户端画像）片长 " + (int) total + "s / " + segs + " 段"
                        + (total < 120 ? "  ❌ 疑似占位片" : "  ✅ 正片"));
                // 对照：换成浏览器 UA + Referer 会被 CDN 302 到占位片，说明 header 不能丢
                double bad = duration(fetch(pu, browserUa, "https://vd.wmvbo.com/"));
                System.out.println(">>> 对照（浏览器 UA + Referer）片长 " + (int) bad + "s"
                        + (bad < 120 ? "  占位片 ✅ 与拦截现象一致" : "  ⚠️ 未被拦截"));
                // 回归点 3：分片（客户端同样带 UA，且常带 Range）
                if (seg != null) {
                    if (!seg.startsWith("http")) seg = pu.substring(0, pu.lastIndexOf('/') + 1) + seg;
                    java.net.HttpURLConnection sc = (java.net.HttpURLConnection) new java.net.URL(seg).openConnection();
                    sc.setRequestProperty("User-Agent", ua);
                    sc.setRequestProperty("Range", "bytes=0-1023");
                    sc.setInstanceFollowRedirects(false);
                    int scode = sc.getResponseCode();
                    if (scode == 200 || scode == 206) {
                        java.io.InputStream si = sc.getInputStream();
                        byte[] sb = new byte[188];
                        int sn = si.read(sb);
                        si.close();
                        System.out.println(">>> 首个分片 → HTTP " + scode + "，首字节 0x"
                                + Integer.toHexString(sb[0] & 0xff)
                                + (sn > 0 && sb[0] == 0x47 ? " (MPEG-TS ✅)" : " ⚠️ 不是 TS"));
                    } else {
                        System.out.println(">>> 首个分片 → " + scode + "  ❌ " + sc.getHeaderField("Location"));
                    }
                    sc.disconnect();
                }
                // 兜底通道（ext={"proxy":true}）：地址是本机中继，用最糟糕画像也应能拿到正片
                if (pu.contains("127.0.0.1")) {
                    String relayed = fetch(pu, browserUa, "https://vd.wmvbo.com/");
                    System.out.println(">>> 本机中继（浏览器 UA + Referer）片长 " + (int) duration(relayed) + "s"
                            + (duration(relayed) < 120 ? "  ❌ 中继失效" : "  ✅ 中继有效")
                            + (relayed.contains("127.0.0.1") ? "，分片地址已改写为本机 ✅" : "，⚠️ 分片仍指向外网"));
                }
            }

            log("SEARCH 凡人修仙传", s.searchContent("凡人修仙传", false));
            return;
        }

        if (which.equals("vv3")) {
            Vv3 s = new Vv3();
            s.init(null, "");
            log("HOME", s.homeContent(true));
            String c1 = s.categoryContent("1", "1", false, new HashMap<String, String>());
            log("CAT-1-p1", c1);
            log("CAT-1-p2", s.categoryContent("1", "2", false, new HashMap<String, String>()));

            JSONArray list = new JSONObject(c1).optJSONArray("list");
            String id = args.length > 1 ? args[1] : list.getJSONObject(0).optString("vod_id");
            String d = s.detailContent(java.util.Collections.singletonList(id));
            log("DETAIL " + id, d);

            JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
            System.out.println("vod_name=" + vod.optString("vod_name")
                    + " | type=" + vod.optString("type_name")
                    + " | area=" + vod.optString("vod_area")
                    + " | year=" + vod.optString("vod_year")
                    + " | score=" + vod.optString("vod_score")
                    + " | remarks=" + vod.optString("vod_remarks")
                    + " | actor=" + vod.optString("vod_actor")
                    + " | director=" + vod.optString("vod_director"));
            String playUrl = vod.optString("vod_play_url");
            System.out.println("play_url[0]=" + cut(playUrl));
            if (playUrl.length() > 0) {
                String firstEp = playUrl.split("#")[0];
                String epId = firstEp.substring(firstEp.indexOf('$') + 1);
                log("PLAYER " + epId, s.playerContent("vv3nwjk", epId, new ArrayList<String>()));
            }

            log("SEARCH 爱情", s.searchContent("爱情", false));
        } else if (which.equals("jianpian")) {
            com.github.catvod.spider.Jianpian s = new com.github.catvod.spider.Jianpian();
            s.init(null, args.length > 2 ? args[2] : "");
            long t0 = System.currentTimeMillis();
            String home = s.homeContent(true);
            System.out.println(">>> homeContent 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            log("HOME", home);
            log("HOME-VIDEO", s.homeVideoContent());

            String c1 = s.categoryContent("movie", "1", false, new HashMap<String, String>());
            JSONObject c1o = new JSONObject(c1);
            System.out.println(">>> 电影 p1 条数=" + c1o.optJSONArray("list").length()
                    + " pagecount=" + c1o.optInt("pagecount"));
            String c2 = s.categoryContent("movie", "2", false, new HashMap<String, String>());
            System.out.println(">>> 电影 p2 条数=" + new JSONObject(c2).optJSONArray("list").length()
                    + " 首id=" + new JSONObject(c2).optJSONArray("list").getJSONObject(0).optString("vod_id")
                    + " (p1 首id=" + c1o.optJSONArray("list").getJSONObject(0).optString("vod_id") + ")");
            HashMap<String, String> ext = new HashMap<String, String>();
            ext.put("area", "美国");
            ext.put("year", "2025");
            String cf = s.categoryContent("movie", "1", true, ext);
            System.out.println(">>> 筛选(美国/2025) 条数=" + new JSONObject(cf).optJSONArray("list").length());
            log("CAT-movie-filter", cf);
            log("CAT-series-p1", s.categoryContent("series", "1", false, new HashMap<String, String>()));

            String s1 = s.searchContent("凡人修仙传", false);
            System.out.println(">>> 搜索条数=" + new JSONObject(s1).optJSONArray("list").length());
            log("SEARCH 凡人修仙传", s1);
            String s2 = s.searchContent("凡人修仙传", false, "2");
            System.out.println(">>> 搜索 p2 条数=" + new JSONObject(s2).optJSONArray("list").length());

            String id = args.length > 1 ? args[1]
                    : new JSONObject(s1).optJSONArray("list").getJSONObject(0).optString("vod_id");
            String d = s.detailContent(java.util.Collections.singletonList(id));
            log("DETAIL " + id, d);
            JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
            System.out.println("vod_name=" + vod.optString("vod_name")
                    + " | type=" + vod.optString("type_name")
                    + " | area=" + vod.optString("vod_area")
                    + " | year=" + vod.optString("vod_year")
                    + " | remarks=" + vod.optString("vod_remarks")
                    + " | content=" + cut(vod.optString("vod_content")));
            System.out.println("from=" + vod.optString("vod_play_from"));
            String playUrl = vod.optString("vod_play_url");
            System.out.println("play_url[0]=" + cut(playUrl));
            if (playUrl.length() > 0) {
                String block = playUrl.split("\\$\\$\\$")[0];
                String firstEp = block.split("#")[0];
                String lastEp = block.split("#")[block.split("#").length - 1];
                String epId = firstEp.substring(firstEp.indexOf('$') + 1);
                String epIdLast = lastEp.substring(lastEp.indexOf('$') + 1);
                log("PLAYER " + epId, s.playerContent("荐片", epId, new ArrayList<String>()));
                log("PLAYER-last " + epIdLast, s.playerContent("荐片", epIdLast, new ArrayList<String>()));
                // 取流验证
                JSONObject pc = new JSONObject(s.playerContent("荐片", epId, new ArrayList<String>()));
                String u = pc.optString("url");
                if (u.length() > 0) {
                    String m3u8 = fetch(u, null, null);
                    System.out.println(">>> m3u8 时长=" + duration(m3u8) + "s 分片=" + segCount(m3u8));
                }
            }
        } else if (which.equals("saohuo")) {
            com.github.catvod.spider.SaoHuo s = new com.github.catvod.spider.SaoHuo();
            s.init(null, args.length > 2 ? args[2] : "");
            String home = s.homeContent(true);
            System.out.println(">>> HOME 返回长度=" + home.length());
            log("HOME", home);
            String c1 = s.categoryContent("1", "1", false, new HashMap<String, String>());
            System.out.println(">>> 电影 p1 条数=" + new JSONObject(c1).optJSONArray("list").length());
            String c2 = s.categoryContent("1", "2", false, new HashMap<String, String>());
            System.out.println(">>> 电影 p2 条数=" + new JSONObject(c2).optJSONArray("list").length());
            HashMap<String, String> ext = new HashMap<String, String>();
            ext.put("tid", "9"); // 动作片
            String cf = s.categoryContent("1", "1", true, ext);
            System.out.println(">>> 电影-动作片 条数=" + new JSONObject(cf).optJSONArray("list").length());
            String sr = s.searchContent("凡人修仙传", false);
            System.out.println(">>> 搜索条数=" + new JSONObject(sr).optJSONArray("list").length());
            log("SEARCH 凡人修仙传", sr);
            JSONArray arr = new JSONObject(sr).optJSONArray("list");
            if (arr.length() == 0) arr = new JSONObject(c1).optJSONArray("list");
            if (arr.length() > 0) {
                String id = arr.getJSONObject(0).optString("vod_id");
                String d = s.detailContent(java.util.Collections.singletonList(id));
                log("DETAIL " + id, d);
                JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
                System.out.println("name=" + vod.optString("vod_name")
                        + " | remarks=" + vod.optString("vod_remarks")
                        + " | from=" + vod.optString("vod_play_from"));
                String pu = vod.optString("vod_play_url");
                System.out.println("play_url 片段=" + cut(pu));
                if (pu.length() > 0) {
                    String firstBlock = pu.split("\\$\\$\\$")[0];
                    String epId = firstBlock.split("#")[0];
                    epId = epId.substring(epId.indexOf('$') + 1);
                    log("PLAYER " + epId, s.playerContent("骚火", epId, new ArrayList<String>()));
                }
            }
        } else {
            Kky s = new Kky();
            s.init(null, "");
            log("HOME", s.homeContent(true));
            String c1 = s.categoryContent("1", "1", false, new HashMap<String, String>());
            log("CAT-1-p1", c1);
            log("CAT-1-p2", s.categoryContent("1", "2", false, new HashMap<String, String>()));
            HashMap<String, String> ext = new HashMap<String, String>();
            ext.put("area", "美国");
            ext.put("year", "2024");
            log("CAT-1-filter(美国/2024)", s.categoryContent("1", "1", true, ext));

            JSONArray list = new JSONObject(c1).optJSONArray("list");
            System.out.println("首页条数=" + list.length());
            String id = args.length > 1 ? args[1] : list.getJSONObject(0).optString("vod_id");
            String d = s.detailContent(java.util.Collections.singletonList(id));
            log("DETAIL " + id, d);

            JSONObject vod = new JSONObject(d).optJSONArray("list").getJSONObject(0);
            System.out.println("vod_name=" + vod.optString("vod_name")
                    + " | type=" + vod.optString("type_name")
                    + " | area=" + vod.optString("vod_area")
                    + " | year=" + vod.optString("vod_year")
                    + " | actor=" + vod.optString("vod_actor")
                    + " | director=" + vod.optString("vod_director")
                    + " | remarks=" + vod.optString("vod_remarks")
                    + " | content=" + vod.optString("vod_content"));
            String from = vod.optString("vod_play_from");
            String playUrl = vod.optString("vod_play_url");
            System.out.println("from=" + from);
            System.out.println("play_url[0]=" + cut(playUrl));
            if (playUrl.length() > 0) {
                String block = playUrl.split("\\$\\$\\$")[0];
                String firstEp = block.split("#")[0];
                String epId = firstEp.substring(firstEp.indexOf('$') + 1);
                log("PLAYER " + epId, s.playerContent("可可影视", epId, new ArrayList<String>()));
            }

            log("SEARCH 爱情", s.searchContent("爱情", false));
        }
    }
}
