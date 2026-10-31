package dev.cordis.core.runtime;

import dev.cordis.core.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件运行时记录:同一个插件共用的一份档案,该插件实例化出的
 * 所有运行实例(fiber)都登记在这里。
 */
public final class PluginRuntime {

    /** 插件本体,也是注册表的 key。 */
    public final Plugin plugin;
    /** 插件显示名。 */
    public final String name;
    /** 该插件当前存活的所有运行实例(fiber)(每次实例化一个)。 */
    public final List<Fiber> fibers = new ArrayList<>();

    PluginRuntime(Plugin plugin) {
        this.plugin = plugin;
        String n = plugin.name();
        this.name = n == null || n.isEmpty() ? plugin.getClass().getSimpleName() : n;
    }

    @Override
    public String toString() {
        return "Runtime<" + name + ">";
    }
}
