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
                // 关键回归点：CDN 只看请求头（UA 含 Mozilla 或带任意 Referer → 302 到广告占位片，
                // 客户端「播」出来的就是那支占位片）。播放器内核常无视 header，所以现在下发的是
                // 本机回环中继地址；这里故意用最糟糕的画像（浏览器 UA + Referer）打中继，
                // 能拿到正片才算通过。
                JSONObject pr = new JSONObject(pj);
                String pu = pr.optString("playUrl", "");
                Object hobj = pr.opt("header");
                System.out.println(">>> header 类型 = " + (hobj instanceof String ? "String ✅"
                        : hobj == null ? "缺失 ❌" : "JSONObject ❌（客户端会忽略）"));
                System.out.println(">>> 下发地址 = " + pu
                        + (pu.contains("127.0.0.1") ? "  ✅ 本机中继" : "  ⚠️ 直链（中继未启动）"));
                final String browserUa = "Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(pu).openConnection();
                conn.setRequestProperty("User-Agent", browserUa);          // 浏览器 UA
                conn.setRequestProperty("Referer", "https://vd.wmvbo.com/"); // 且带 Referer
                conn.setInstanceFollowRedirects(false);
                int hcode = conn.getResponseCode();
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
                System.out.println(">>> 中继播放列表 HTTP " + hcode + "，片长 " + (int) total + "s / " + segs + " 段"
                        + (total < 120 ? "  ⚠️ 疑似占位片" : "  ✅ 正片")
                        + (m3u8.contains("127.0.0.1") ? "，分片地址已改写为本机 ✅" : "，⚠️ 分片仍指向外网"));
                conn.disconnect();

                // 分片同样要走中继（CDN 对 ts 一样拦），用同一套「浏览器 UA + Referer」验证
                String seg = null;
                for (String line : m3u8.split("\n")) {
                    String t = line.trim();
                    if (t.length() > 0 && !t.startsWith("#")) { seg = t; break; }
                }
                if (seg != null) {
                    if (!seg.startsWith("http")) {
                        String base = pu.substring(0, pu.lastIndexOf('/') + 1);
                        seg = base + seg;
                    }
                    java.net.HttpURLConnection sc = (java.net.HttpURLConnection) new java.net.URL(seg).openConnection();
                    sc.setRequestProperty("User-Agent", browserUa);
                    sc.setRequestProperty("Referer", "https://vd.wmvbo.com/");
                    sc.setRequestProperty("Range", "bytes=0-1023");   // 播放器常用的 Range 请求
                    sc.setInstanceFollowRedirects(false);
                    int scode = sc.getResponseCode();
                    String sloc = sc.getHeaderField("Location");
                    if (scode == 200 || scode == 206) {
                        java.io.InputStream si = sc.getInputStream();
                        byte[] sb = new byte[188];
                        int sn = si.read(sb);
                        si.close();
                        System.out.println(">>> 中继分片 " + seg.substring(seg.lastIndexOf('/') + 1)
                                + " → HTTP " + scode + "，首字节 0x" + Integer.toHexString(sb[0] & 0xff)
                                + (sn > 0 && sb[0] == 0x47 ? " (MPEG-TS ✅)" : " ⚠️ 不是 TS"));
                    } else {
                        System.out.println(">>> 中继分片 → " + scode
                                + (sloc == null ? " ❌" : " → " + sloc + " ❌ 仍被劫持"));
                    }
                    sc.disconnect();

                    // 原始 socket 校验：中继的 Content-Length 必须与实际响应体一致（播放器最怕这个）
                    java.net.Socket sock = new java.net.Socket("127.0.0.1",
                            Integer.parseInt(pu.substring("http://127.0.0.1:".length(), pu.indexOf('/', 17))));
                    sock.setSoTimeout(20000);
                    java.io.OutputStream so = sock.getOutputStream();
                    String segPath = seg.substring(seg.indexOf('/', 17));
                    so.write(("GET " + segPath + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                            + "User-Agent: " + browserUa + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                    so.flush();
                    java.io.InputStream sin = sock.getInputStream();
                    StringBuilder hb = new StringBuilder();
                    int ch, prev = 0, clen = -1;
                    while ((ch = sin.read()) >= 0) {
                        hb.append((char) ch);
                        if (prev == '\r' && ch == '\n' && hb.indexOf("\r\n\r\n") >= 0) break;
                        prev = ch;
                    }
                    for (String hl : hb.toString().split("\r\n")) {
                        if (hl.toLowerCase().startsWith("content-length:")) {
                            clen = Integer.parseInt(hl.substring(15).trim());
                        }
                    }
                    int body = 0;
                    byte[] rbuf = new byte[65536];
                    int rn;
                    while ((rn = sin.read(rbuf)) > 0) body += rn;
                    sock.close();
                    System.out.println(">>> 中继原始响应 framing: 声明 Content-Length=" + clen
                            + "，实际响应体=" + body + (clen == body ? "  ✅ 一致" : "  ❌ 不一致"));
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
