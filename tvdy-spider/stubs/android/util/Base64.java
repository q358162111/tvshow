package android.util;

/**
 * 仅编译期占位，不会打包进 jar（运行时由 Android 提供真实实现）。
 * 这里保留一份等价实现，便于在 JVM 上用 test/TestSpider.java 做本地联调。
 */
public class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    public static byte[] decode(String str, int flags) {
        if (str == null) return new byte[0];
        return java.util.Base64.getMimeDecoder().decode(str);
    }

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return "";
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
