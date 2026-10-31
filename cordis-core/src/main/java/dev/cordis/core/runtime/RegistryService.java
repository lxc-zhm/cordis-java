package dev.cordis.core.runtime;

import dev.cordis.core.CordisException;
import dev.cordis.core.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件注册表:管理“哪个插件有哪些运行实例(fiber)”(挂在根上下文上)。
 *
 * <p>{@link #plugin} 是启动入口:创建或复用插件的运行时记录,
 * 在当前上下文下实例化一个 {@link Fiber}。
 * “挂载”这个动作本身是父实例的一个效应——卸载父实例时会自动连带卸载子实例。</p>
 */
public final class RegistryService {

    @SuppressWarnings("unused")
    private final Context root;
    private final Map<Plugin, PluginRuntime> internal = new LinkedHashMap<>();
    private int counter = 0;

    RegistryService(Context root) {
        this.root = root;
    }

    /**
     * 分配下一个运行实例编号(每调用一次加一)。
     */
    int nextUid() {
        return ++counter;
    }

    /**
     * 已注册的插件数量。
     */
    public int size() {
        return root.atomic(internal::size);
    }

    /**
     * 所有已注册的插件运行时记录。
     */
    public Collection<PluginRuntime> runtimes() {
        return root.atomic(() -> List.copyOf(internal.values()));
    }

    /**
     * 查某个插件的运行时记录,没注册返回 null。
     */
    public PluginRuntime get(Plugin plugin) {
        return root.atomic(() -> internal.get(plugin));
    }

    /**
     * 卸载某插件的全部运行实例,并删除它的运行时记录。
     */
    public void delete(Plugin plugin) {
        List<Fiber> fibers = root.atomic(() -> {
            PluginRuntime runtime = internal.get(plugin);
            return runtime == null ? List.<Fiber>of() : List.copyOf(runtime.fibers);
        });
        for (Fiber fiber : fibers) {
            fiber.dispose();
        }
    }

    void removeRuntime(PluginRuntime runtime) {
        root.atomic(() -> {
            internal.remove(runtime.plugin, runtime);
            return null;
        });
    }

    /**
     * 卸载所有插件的所有运行实例,并清空注册表(应用关闭时用)。
     */
    public void deleteAll() {
        List<Fiber> fibers = root.atomic(() -> internal.values().stream().flatMap(runtime -> List.copyOf(runtime.fibers).stream()).toList());
        // Issue every retirement before waiting, so dependencies can drain without order constraints.
        var tasks = fibers.stream().map(Fiber::disposeAsync).map(java.util.concurrent.CompletionStage::toCompletableFuture)
                .toArray(java.util.concurrent.CompletableFuture[]::new);
        if (!root.inRuntime()) dev.cordis.core.effect.Stages.join(java.util.concurrent.CompletableFuture.allOf(tasks));
    }

    /**
     * 在 ctx 里启动一个插件(带上配置)。
     *
     * @return 新建的运行实例;之后它的生命周期由依赖变化自动驱动
     */
    public Fiber plugin(Context ctx, Plugin plugin, Object config) {
        return root.atomic(() -> {
            if (plugin == null) throw new CordisException(CordisException.Code.INVALID_PLUGIN, "plugin is null");
            ctx.fiber().assertActive();
            PluginRuntime runtime = internal.computeIfAbsent(plugin, PluginRuntime::new);
            return new Fiber(ctx, config, plugin, runtime);
        });
    }

    /**
     * 简写:等依赖的服务都可用时执行回调;依赖更换时会自动卸载重跑。
     */
    public Fiber inject(Context ctx, List<String> deps, java.util.function.BiFunction<Context, Object, Object> body) {
        Plugin plugin = Plugin.of(null, deps == null ? List.of() : deps, body);
        return plugin(ctx, plugin, null);
    }

    /**
     * 把依赖名列表整理成映射(值先放 null,保留声明顺序)。
     */
    static Map<String, Object> resolveInject(List<String> inject) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (inject != null) for (String name : inject) map.put(name, null);
        return map;
    }
}
