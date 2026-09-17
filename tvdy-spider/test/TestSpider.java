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
            log("HOME", s.homeContent(true));
            log("HOME-VIDEO", s.homeVideoContent());
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
                log("PLAYER " + epId, s.playerContent("瓜子", epId, new ArrayList<String>()));
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
