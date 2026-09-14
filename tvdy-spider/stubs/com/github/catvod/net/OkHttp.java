package com.github.catvod.net;

import java.util.Map;

/** 仅编译期占位，不会打包进 jar（运行时由 TVBox 宿主提供） */
public class OkHttp {

    public static String string(String url, Map<String, String> header) {
        return "";
    }

    public static String string(String url) {
        return "";
    }
}
