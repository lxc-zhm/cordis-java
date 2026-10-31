package dev.cordis.loader;

import dev.cordis.core.runtime.Context;
import dev.cordis.core.runtime.Fiber;
import dev.cordis.core.runtime.Logger;
import dev.cordis.core.Plugin;
import dev.cordis.core.coeffect.RealmKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 组件加载器(声明式):用一列配置条目({@link Entry})描述“想要装什么”,
 * 它负责对着清单干活,清单变化时做增量调整(而不是全部推倒重来)。
 *
 * <p>调和(reconcile)按 id 差分:消失的卸载、新增的挂载、存活的按“哪个字段变了”选最小动作。
 * 为什么可以这么省事?框架保证:无论中途怎么装怎么卸,最终停下来时的状态
 * 和“从头按最终配置装一遍”完全一致(论文 Theorem 80)。</p>
 */
public final class ComponentLoader {

    /** 一条已挂载记录:配置条目、运行实例(fiber)、插件、派生上下文。 */
    public static final class Mounted {
        public final Entry entry;
        public final Fiber fiber;   // 停用或启动失败时为 null
        public final Plugin plugin; // 停用时为 null
        public final Context ctx;

        Mounted(Entry entry, Fiber fiber, Plugin plugin, Context ctx) {
            this.entry = entry;
            this.fiber = fiber;
            this.plugin = plugin;
            this.ctx = ctx;
        }

        Mounted with(Entry entry, Fiber fiber, Plugin plugin) {
            return new Mounted(entry, fiber, plugin, this.ctx);
        }
    }

    private final Context parent;
    private final PluginResolver resolver;
    private final Map<String, Mounted> mounted = new LinkedHashMap<>();
    private final Logger log;

    public ComponentLoader(Context parent, PluginResolver resolver) {
        this.parent = parent;
        this.resolver = resolver;
        this.log = parent.logger("loader");
    }

    /** 查某个 id 的已挂载记录,没有返回 null。 */
    public synchronized Mounted mounted(String id) {
        return mounted.get(id);
    }

    /** 所有已挂载的 id,按加载顺序排列。 */
    public synchronized List<String> ids() {
        return new ArrayList<>(mounted.keySet());
    }

    /** 按整份配置装载(内部就是调和)。 */
    public synchronized void load(List<Entry> desired) {
        reconcile(desired);
    }

    /**
     * 把运行中的系统调和成 desired 这份清单:被移除的卸载,存活的按字段更新,
     * 新增的按顺序挂载。不用关心先后顺序——依赖没就绪的插件会自动待命等待。
     */
    public synchronized void reconcile(List<Entry> desired) {
        Set<String> seen = new java.util.HashSet<>();
        for (Entry e : desired) seen.add(e.id);
        for (String id : new ArrayList<>(mounted.keySet())) {
            if (!seen.contains(id)) unmount(id);
        }
        for (Entry e : desired) {
            apply(e);
        }
    }

    private void apply(Entry e) {
        Mounted m = mounted.get(e.id);
        if (m == null) {
            if (e.disabled) {
                mounted.put(e.id, new Mounted(e, null, null, null));
            } else {
                mount(e, null);
            }
            return;
        }
        // 逐字段分发:按哪个字段变了,选最小扰动的动作
        if (!Objects.equals(m.entry.url, e.url)) {
            rebuild(e, null); // 身份/组件变了(换了模块)
            return;
        }
        if (m.entry.disabled != e.disabled) {
            if (e.disabled) {
                log.info("disable " + e.id);
                if (m.fiber != null) m.fiber.dispose();
                mounted.put(e.id, new Mounted(e, null, null, m.ctx));
            } else {
                rebuild(e, null); // 重新启用:在同一个 id 下新建一个运行实例
            }
            return;
        }
        if (!Objects.equals(m.entry.isolate, e.isolate)) {
            rebuild(e, m.plugin); // 隔离域重新指派(算法 7,由定理 80 保证)
            return;
        }
        if (!Objects.equals(m.entry.intercept, e.intercept)) {
            rebuild(e, m.plugin); // 拦截配置挂在派生上下文上,需要重建
            return;
        }
        if (!Objects.equals(m.entry.config, e.config)) {
            mounted.put(e.id, m.with(e, m.fiber, m.plugin));
            if (m.fiber != null) {
                log.info("update config of " + e.id);
                try {
                    m.fiber.update(e.config);
                } catch (RuntimeException error) {
                    // 配置不合法:该运行实例进入 FAILED,已挂载记录保留新配置
                    log.error("entry " + e.id + " rejected new config: {}", error);
                }
            }
        }
    }

    private void mount(Entry e, Plugin forced) {
        try {
            Context ctx = derive(e);
            Plugin plugin = forced != null ? forced : resolver.resolve(e.url);
            Fiber fiber = ctx.plugin(plugin, e.config);
            try {
                fiber.await();
            } catch (RuntimeException error) {
                log.error("entry " + e.id + " failed to start: {}", error);
            }
            mounted.put(e.id, new Mounted(e, fiber, plugin, ctx));
            log.info("mounted " + e.id + " (" + fiber.name() + " #" + fiber.uid + ")");
        } catch (Exception error) {
            log.error("entry " + e.id + " could not resolve " + e.url + ": {}", error);
            mounted.put(e.id, new Mounted(e, null, null, null));
        }
    }

    private void rebuild(Entry e, Plugin forced) {
        Mounted m = mounted.get(e.id);
        if (m != null && m.fiber != null) {
            log.info("rebuild " + e.id);
            m.fiber.dispose();
        }
        mount(e, forced);
    }

    private void unmount(String id) {
        Mounted m = mounted.remove(id);
        if (m != null && m.fiber != null) {
            log.info("unmount " + id);
            m.fiber.dispose();
        }
    }

    /** 为 Entry 派生上下文:先套隔离域,再套拦截配置。 */
    private Context derive(Entry e) {
        Context ctx = parent;
        for (Map.Entry<String, Object> i : e.isolate.entrySet()) {
            RealmKey key;
            if (i.getValue() instanceof RealmKey rk) {
                key = rk;
            } else if (Boolean.TRUE.equals(i.getValue())) {
                key = RealmKey.fresh(i.getKey() + "#" + e.id); // 独享域跟随这条配置条目走
            } else {
                key = RealmKey.shared(i.getValue());
            }
            ctx = ctx.isolate(i.getKey(), key);
        }
        for (Map.Entry<String, Object> i : e.intercept.entrySet()) {
            ctx = ctx.intercept(i.getKey(), i.getValue());
        }
        return ctx;
    }

    /**
     * 只换插件、不动配置:HMR 的入口(算法 10)。
     * forced 为 null 时会重新走解析器,从而拿到“改过”的新代码。
     *
     * @return 新的 fiber
     */
    public synchronized Fiber remount(String id, Plugin forced) throws Exception {
        Mounted m = mounted.get(id);
        if (m == null) throw new IllegalArgumentException("entry not mounted: " + id);
        Entry e = m.entry;
        if (m.fiber != null) m.fiber.dispose();
        Plugin plugin = forced != null ? forced : resolver.resolve(e.url);
        Context ctx = derive(e);
        Fiber fiber = ctx.plugin(plugin, e.config);
        fiber.await();
        mounted.put(id, new Mounted(e, fiber, plugin, ctx));
        log.info("hot-swapped " + id + " (" + fiber.name() + " #" + fiber.uid + ")");
        return fiber;
    }

    /** 卸载所有条目并清空加载器。 */
    public synchronized void disposeAll() {
        for (String id : new ArrayList<>(mounted.keySet())) {
            unmount(id);
        }
    }
}
