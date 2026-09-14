package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;

/**
 * 仅编译期占位（方法签名与 TVBox 宿主中的 com.github.catvod.crawler.Spider 一致），
 * 不会打包进 jar，运行时由 TVBox 宿主提供真实实现。
 */
public abstract class Spider {

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }
}
