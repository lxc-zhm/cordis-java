package dev.cordis.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件注册表:管理“哪个插件有哪些 fiber”(挂在根上下文上)。
 *
 * <p>{@link #plugin} 是启动入口:创建或复用插件的运行时记录,
 * 在当前上下文下实例化一个 {@link Fiber}。
 * “挂载”这个动作本身是父 fiber 的一个效果——卸载父 fiber 时会自动连带卸载子 fiber。</p>
 */
public final class RegistryService {

    @SuppressWarnings("unused")
    private final Context root;
    private final Map<Plugin, PluginRuntime> internal = new LinkedHashMap<>();
    private int counter = 0;

    RegistryService(Context root) {
        this.root = root;
    }

    /** 分配下一个 fiber 编号(每调用一次加一)。 */
    synchronized int nextUid() {
        return ++counter;
    }

    /** 已注册的插件数量。 */
    public synchronized int size() {
        return internal.size();
    }

    /** 所有已注册的插件运行时记录。 */
    public synchronized Collection<PluginRuntime> runtimes() {
        return List.copyOf(internal.values());
    }

    /** 查某个插件的运行时记录,没注册返回 null。 */
    public synchronized PluginRuntime get(Plugin plugin) {
        return internal.get(plugin);
    }

    /** 卸载某插件的全部 fiber,并删除它的运行时记录。 */
    public synchronized void delete(Plugin plugin) {
        PluginRuntime runtime = internal.remove(plugin);
        if (runtime == null) return;
        for (Fiber fiber : List.copyOf(runtime.fibers)) {
            fiber.dispose();
        }
    }

    synchronized void removeRuntime(PluginRuntime runtime) {
        internal.remove(runtime.plugin);
    }

    /** 卸载所有插件的所有 fiber,并清空注册表(应用关闭时用)。 */
    public synchronized void deleteAll() {
        List<PluginRuntime> runtimes = new ArrayList<>(internal.values());
        internal.clear();
        for (PluginRuntime runtime : runtimes) {
            for (Fiber fiber : List.copyOf(runtime.fibers)) {
                fiber.dispose();
            }
        }
    }

    /**
     * 在 ctx 里启动一个插件(带上配置)。
     *
     * @return 新建的 fiber;之后它的生命周期由依赖变化自动驱动
     */
    public Fiber plugin(Context ctx, Plugin plugin, Object config) {
        if (plugin == null) throw new CordisException(CordisException.Code.INVALID_PLUGIN, "plugin is null");
        ctx.fiber().assertActive();
        PluginRuntime runtime = internal.computeIfAbsent(plugin, PluginRuntime::new);
        return new Fiber(ctx, config, plugin, runtime);
    }

    /**
     * 简写:等依赖的服务都可用时执行回调;依赖更换时会自动卸载重跑。
     */
    public Fiber inject(Context ctx, List<String> deps, java.util.function.BiFunction<Context, Object, Object> body) {
        Plugin plugin = Plugin.of(null, deps == null ? List.of() : deps, body);
        return plugin(ctx, plugin, null);
    }

    /** 把依赖名列表整理成映射(值先放 null,保留声明顺序)。 */
    static Map<String, Object> resolveInject(List<String> inject) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (inject != null) for (String name : inject) map.put(name, null);
        return map;
    }
}
