package dev.cordis.loader;

import dev.cordis.core.Plugin;

/**
 * 把 Entry 的 url 解析成要安装的插件。
 *
 * <p>Java 没有 Node 那种现成的模块注册表,所以“加载/卸载模块”交给宿主实现:
 * 可以从注册表查、反射加载类、读外部文件;HMR 时返回一个新实例就代表“改过的代码”。</p>
 */
@FunctionalInterface
public interface PluginResolver {

    /**
     * 按 url 找到插件。
     *
     * @return 插件;底层模块可能已变化时,每次调用返回新实例
     * @throws Exception 加载失败(加载器会记录;HMR 会整体回滚)
     */
    Plugin resolve(String url) throws Exception;
}
