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

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "vv3";

        if (which.equals("guazi")) {
            GuaZi s = new GuaZi();
            s.init(null, "");
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
                // 关键回归点：CDN 防盗链，带 Referer 或浏览器 UA 会拿到 20 秒宣传片
                JSONObject pr = new JSONObject(pj);
                String pu = pr.optString("playUrl", "");
                // 回归点 1：header 必须是 JSON 字符串，传 JSONObject 客户端会忽略 → 被 CDN 劫持
                Object hobj = pr.opt("header");
                System.out.println(">>> header 类型 = " + (hobj instanceof String ? "String ✅"
                        : hobj == null ? "缺失 ❌" : "JSONObject ❌（客户端会忽略，必被劫持）"));
                String ua = new JSONObject(pr.optString("header", "{}")).optString("User-Agent", "");
                System.out.println(">>> 下发 UA = [" + ua + "]"
                        + (ua.contains("Mozilla") ? "  ⚠️ 浏览器 UA 会拿到广告" : "  ✅ 非浏览器 UA"));
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(pu).openConnection();
                conn.setRequestProperty("User-Agent", ua);
                conn.setInstanceFollowRedirects(true);
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                String m3u8 = new String(bos.toByteArray(), "UTF-8");
                double total = 0;
                int segs = 0;
                for (String line : m3u8.split("\n")) {
                    if (line.startsWith("#EXTINF:")) {
                        segs++;
                        total += Double.parseDouble(line.substring(8).split(",")[0].trim());
                    }
                }
                System.out.println(">>> 片长 " + (int) total + "s / " + segs + " 段"
                        + (total < 120 ? "  ⚠️ 疑似宣传片/试看" : "  ✅ 正片")
                        + "  final=" + conn.getURL());
                conn.disconnect();

                // 回归点 2：分片请求同样带防盗链，必须能用同一套 header 拉到真实 TS
                String seg = null;
                for (String line : m3u8.split("\n")) {
                    String t = line.trim();
                    if (t.length() > 0 && !t.startsWith("#")) { seg = t; break; }
                }
                if (seg != null) {
                    String base = conn.getURL().toString();
                    base = base.substring(0, base.lastIndexOf('/') + 1);
                    java.net.HttpURLConnection sc = (java.net.HttpURLConnection) new java.net.URL(base + seg).openConnection();
                    sc.setRequestProperty("User-Agent", ua);
                    sc.setInstanceFollowRedirects(false);
                    int scode = sc.getResponseCode();
                    String sloc = sc.getHeaderField("Location");
                    if (scode == 200) {
                        java.io.InputStream si = sc.getInputStream();
                        byte[] sb = new byte[188];
                        int sn = si.read(sb);
                        si.close();
                        System.out.println(">>> 首个分片 " + seg + " → 200，首字节 0x"
                                + Integer.toHexString(sb[0] & 0xff)
                                + (sn > 0 && sb[0] == 0x47 ? " (MPEG-TS ✅)" : " ⚠️ 不是 TS"));
                    } else {
                        System.out.println(">>> 首个分片 " + seg + " → " + scode
                                + (sloc == null ? " ❌" : " → " + sloc + " ❌ 被劫持到广告站"));
                    }
                    sc.disconnect();
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
