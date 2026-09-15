package com.github.catvod.spider;

import android.content.Context;

/**
 * takagen99/Box 的 JarLoader.loadClassLoader() 对**每个** jar（含站点级 jar）
 * 都会强制做如下校验：
 * <pre>
 *   Class&lt;?&gt; classInit = classLoader.loadClass("com.github.catvod.spider.Init");
 *   if (classInit != null) {
 *       Method initMethod = classInit.getMethod("init", Context.class);
 *       initMethod.invoke(null, App.getInstance());
 *       success = true;
 *   }
 *   ...
 *   if (success) classLoaders.put(key, classLoader);   // 失败则不注册
 * </pre>
 * 若 jar 里没有 Init，则 success 恒为 false，classLoader 不会注册，
 * loadJarInternal() 返回 null，getSpider() 进而返回 SpiderNull —— 站点全空白。
 * <p>
 * 因此这里提供一个空实现，仅用于通过宿主的校验。站点 jar 无需任何全局初始化，
 * 真正的初始化由 TvDy.init(Context, String) 完成。
 */
public class Init {

    public static void init(Context context) {
        // no-op：仅为通过 takagen99/Box JarLoader 的存在性校验
    }
}
