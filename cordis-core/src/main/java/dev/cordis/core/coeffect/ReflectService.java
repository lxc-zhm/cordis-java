package dev.cordis.core.coeffect;

import dev.cordis.core.runtime.Context;
import dev.cordis.core.runtime.Fiber;
import dev.cordis.core.runtime.FiberState;
import dev.cordis.core.runtime.PluginRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * 服务仓库:整个应用的服务绑定都存这里(挂在根上下文上)。
 *
 * <p>绑定按隔离域分 key:同一个服务名,每个隔离域可以各有一条绑定。
 * {@link #notify} 是“广播站”:绑定一有变化,就通知所有声明了该服务的 fiber
 * 重新检查依赖——依赖齐了就启动,缺了就卸载。“响应式”就来自这里。</p>
 */
public final class ReflectService {

    final Context root;

    /** 每个服务名的默认隔离域,第一次 provide 时创建。 */
    final Map<String, RealmKey> globalRealms = new LinkedHashMap<>();
    /** 服务绑定表:key 是隔离域,value 是绑定。 */
    final Map<RealmKey, Impl> store = new LinkedHashMap<>();

    public ReflectService(Context root) {
        this.root = root;
    }

    /** 取某服务名的默认共享域,没有就创建。 */
    public RealmKey globalRealm(String name) {
        RealmKey key = globalRealms.get(name);
        if (key == null) {
            key = RealmKey.fresh(name);
            globalRealms.put(name, key);
        }
        return key;
    }

    /**
     * 查找服务绑定:从 from 这个上下文的视角解析隔离域,
     * 所以同一个名字在不同范围里可能查到不同结果。
     *
     * @param strict 为 true 时,只返回提供者 fiber 正处于 ACTIVE 状态的绑定
     */
    public Impl getImpl(Context from, String name, boolean strict) {
        RealmKey key = from.resolveRealm(name);
        Impl impl = store.get(key);
        if (impl == null) return null;
        if (strict && impl.fiber.state != FiberState.ACTIVE) return null;
        return impl;
    }

    /** 读取某隔离域上的一条绑定;没有返回 null。 */
    public Impl bindingAt(RealmKey key) {
        return store.get(key);
    }

    /** 写入(或覆盖)一条绑定。 */
    public void bind(RealmKey key, Impl impl) {
        store.put(key, impl);
    }

    /** 注销一条绑定。 */
    public void unbind(RealmKey key) {
        store.remove(key);
    }

    /** 列出某个 fiber 提供的所有绑定(供生命周期通知使用)。 */
    public java.util.List<Impl> bindingsOf(Fiber fiber) {
        java.util.List<Impl> out = new java.util.ArrayList<>();
        for (Impl impl : store.values()) {
            if (impl.fiber == fiber) out.add(impl);
        }
        return out;
    }

    /** 默认通知范围:只通知与发起方在同一隔离域里解析该服务的 fiber。 */
    static BiPredicate<Context, String> realmFilter(Context origin) {
        return (ctx, name) -> ctx.resolveRealm(name) == origin.resolveRealm(name);
    }

    /**
     * 绑定变化后,把所有依赖这些服务的 fiber 重新检查一遍:
     * 依赖由缺变齐就启动,由齐变缺就卸载;没有实质变化的 fiber 重算一遍也无副作用。
     *
     * @return 被重新检查过的 fiber 列表
     */
    public List<Fiber> notify(Context origin, List<String> names) {
        BiPredicate<Context, String> filter = realmFilter(origin);
        List<Fiber> fibers = new ArrayList<>();
        for (PluginRuntime runtime : List.copyOf(root.registry().runtimes())) {
            for (Fiber fiber : List.copyOf(runtime.fibers)) {
                boolean hasUpdate = false;
                for (String name : names) {
                    if (!fiber.inject.containsKey(name)) continue;
                    if (!filter.test(fiber.ctx, name)) continue;
                    hasUpdate = true;
                    fiber.checkImpl(name);
                }
                if (!hasUpdate) continue;
                fiber.refresh();
                fibers.add(fiber);
            }
        }
        for (String name : names) {
            Impl impl = getImpl(root, name, false);
            root.events().emit(null, "internal/service", name, impl == null ? null : impl.value);
        }
        return fibers;
    }
}
