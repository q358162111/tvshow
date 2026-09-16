package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * 瓜子影视  https://www.guazi-ys.com.cn
 * <p>
 * 已查证的事实（写入本 jar 作为文档存根）：
 * <ul>
 *   <li>官网（前端 SEO 落地页）：{@link #SITE_HOST}</li>
 *   <li>推测 API 网关：{@link #API_HOST}（当前 DNS 解析失败，可能已下线）</li>
 *   <li>csp_AppgzGuard 类不在主要开源仓库（FongMi/CatVodSpider、bizhangjie/CatVodSpider、
 *       drizzle888/CatVodTVSpider）和本项目 lib/ 下的第三方 jar（custom_spider.jar /
 *       xry.jar / danmu.jar）中。该源仍然依赖宿主 App 默认 jar 提供的实现。</li>
 * </ul>
 *
 * <p>本类仅为地址存根，不实现完整接口。x.json 中"瓜子"条目的 api 字段保持 csp_AppgzGuard，
 * 由宿主 App 默认 jar 提供。</p>
 */
public class GuaZi extends Spider {

    public static final String SITE_HOST = "https://www.guazi-ys.com.cn";
    public static final String API_HOST  = "https://api.guazi-ys.com.cn";

    @Override
    public void init(Context context, String extend) {
    }

    @Override
    public String homeContent(boolean filter) {
        return new JSONObject().put("class", new JSONArray()).toString();
    }

    @Override
    public String homeVideoContent() {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "{\"parse\":0,\"url\":\"\"}";
    }
}