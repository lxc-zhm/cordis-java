package dev.cordis.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 插件的一次“运行实例”:一个插件装一次,就是一个 fiber。
 *
 * <p>fiber 记录三样东西:</p>
 * <ul>
 *   <li><b>依赖快照</b>(store)—— 启动那一刻,各个依赖服务分别由谁提供;</li>
 *   <li><b>目标视图</b>(target)—— “我的每个依赖现在由谁提供”的摘要;</li>
 *   <li><b>撤销动作栈</b> —— 登记过的所有效果怎么撤销。</li>
 * </ul>
 *
 * <p>工作方式:依赖有变化就重算 target;target 变了就触发加载(reload)
 * 或卸载(unload)。转换一旦开始就一口气跑完,跑完再对比最新 target,
 * 不一致就接着跑下一个转换——保证不会停在“改到一半”的中间状态。</p>
 *
 * <p>卸载顺序有讲究:提供者停止服务后,先让所有依赖它的 fiber 卸载完,
 * 最后才跑提供者自己的撤销动作——这样依赖方的收尾代码还能正常用到依赖。</p>
 */
public final class Fiber {

    static final String INACTIVE = "__INACTIVE__";

    /** 唯一编号;根 fiber 是 0,卸载后置为 null。 */
    public Long uid;
    /** 从哪个上下文加载的(父上下文)。 */
    public final Context parentCtx;
    /** 本 fiber 的插件运行所在的上下文(父上下文的子上下文)。 */
    public final Context ctx;
    /** 声明的依赖:服务名 → 拦截配置(没有就放 null)。 */
    public final Map<String, Object> inject;
    /** 所属插件的共用记录;根 fiber 为 null。 */
    public final PluginRuntime runtime;
    /** 插件定义。 */
    public final Plugin plugin;

    /** 加载期间的依赖快照(记录每个依赖是谁提供的);未加载时为 null。 */
    public Map<String, Impl> store;
    /** 校验后的配置。 */
    public Object config;
    /** 原始配置,每次激活前重新校验。 */
    public Object rawConfig;
    /** 当前生命周期状态;每次变化都会发 internal/status 事件。 */
    public FiberState state = FiberState.PENDING;
    /** 目标视图摘要:每个依赖的提供者编号拼起来;有依赖缺失时为 INACTIVE。 */
    public String target = INACTIVE;

    private Disposable retireHandle;

    // ---- internals
    final Map<String, Impl> pendingStore = new LinkedHashMap<>();
    String epoch = INACTIVE;
    Runnable inertia = null;
    Throwable error;
    final List<EffectRecord> records = new ArrayList<>();
    final Deque<EffectMeta> metaStack = new ArrayDeque<>();

    /** 一条已登记的效果:撤销动作 + 诊断信息。 */
    static final class EffectRecord {
        final Disposable disposable;
        final EffectMeta meta;

        EffectRecord(Disposable disposable, EffectMeta meta) {
            this.disposable = disposable;
            this.meta = meta;
        }
    }

    Fiber(Context parentCtx, Object rawConfig, Plugin plugin, PluginRuntime runtime) {
        this.parentCtx = parentCtx;
        this.rawConfig = rawConfig;
        this.plugin = plugin;
        this.runtime = runtime;
        this.inject = runtime != null
                ? RegistryService.resolveInject(plugin.inject())
                : new LinkedHashMap<>();
        Context root = parentCtx.getRoot();

        if (runtime != null) {
            this.uid = (long) root.registry().nextUid();
            this.ctx = parentCtx.deriveChild(this);
            for (Map.Entry<String, Object> e : inject.entrySet()) {
                if (e.getValue() != null) ctx.interceptSelf(e.getKey(), e.getValue());
            }

            // The instantiation primitive (paper Definition 52): this effect on the
            // parent is O-Insert; its inverse (the Disposable below) is O-Retire, so
            // unloading a parent cascades to its children.
            this.retireHandle = parentCtx.fiber().effect(cb -> {
                runtime.fibers.add(this);
                return (Disposable) () -> {
                    this.uid = null;
                    root.events().emit(null, "internal/plugin", this);
                    runtime.fibers.remove(this);
                    if (runtime.fibers.isEmpty()) root.registry().removeRuntime(runtime);
                    setEpoch(INACTIVE);
                    runChain();
                    // A PENDING fiber may already own effects; explicitly unload them.
                    if (inertia == null && !records.isEmpty()) {
                        state = FiberState.UNLOADING;
                        inertia = this::unloadCycle;
                        runChain();
                    }
                };
            }, "ctx.plugin()");

            root.events().emit(null, "internal/plugin", this);

            if (uid != null && parentCtx.fiber().state != FiberState.UNLOADING) {
                for (String name : inject.keySet()) checkImpl(name);
                refresh();
            }
        } else {
            // root fiber
            this.uid = 0L;
            this.ctx = parentCtx;
            this.state = FiberState.ACTIVE;
            this.store = new LinkedHashMap<>();
            this.target = "";
            this.epoch = "";
        }
    }

    /** 插件显示名:从自己往上找最近的命名祖先;都没有就是 root。 */
    public String name() {
        Fiber f = this;
        while (f.runtime != null) {
            if (f.runtime.name != null && !f.runtime.name.isEmpty()) return f.runtime.name;
            f = f.parentCtx.fiber();
        }
        return "root";
    }

    /** 已卸载就抛异常(防止在死掉的 fiber 上继续登记效果)。 */
    public void assertActive() {
        if (uid != null) return;
        throw new CordisException(CordisException.Code.INACTIVE_EFFECT);
    }

    // ------------------------------------------------------------------ effects

    /**
     * 登记一个“可撤销的效果”(本框架最核心的机制)。
     *
     * <p>回调立即执行;它返回的撤销动作会被收进本 fiber 的栈里,
     * 卸载时(或手动调用返回值时)按逆序执行。返回的 disposer 调两次无害。</p>
     *
     * <p>回调返回惰性 Iterable 时按“阶段”逐个取:每取一个阶段前先检查
     * 目标是否还成立,不成立就停下——已完成的部分照常登记,未开始的不再执行。</p>
     *
     * @param callback 效果体
     * @param label    诊断用的名字(见 getEffects)
     * @return 手动撤销该效果的动作
     */
    public Disposable effect(EffectCallback callback, String label) {
        assertActive();
        if (state == FiberState.UNLOADING) {
            throw new CordisException(CordisException.Code.INACTIVE_EFFECT);
        }
        List<Disposable> local = new ArrayList<>();
        boolean[] armed = {true};
        EffectMeta meta = new EffectMeta(label);
        if (!metaStack.isEmpty()) metaStack.peek().children.add(meta);
        metaStack.push(meta);
        try {
            Object result = callback.execute(ctx);
            collectResult(result, local, armed[0] ? () -> armed[0] : () -> false);
        } catch (Throwable t) {
            runReverse(local); // rollback partial effects
            throw t;
        } finally {
            metaStack.pop();
        }
        EffectRecord[] self = new EffectRecord[1];
        Disposable wrapper = () -> {
            if (!armed[0]) return;
            armed[0] = false;
            records.remove(self[0]);
            runReverse(local);
        };
        self[0] = new EffectRecord(wrapper, meta);
        records.add(self[0]);
        return wrapper;
    }

    private static void collectResult(Object result, List<Disposable> sink, BooleanSupplier guard) {
        if (result == null) return;
        if (result instanceof Disposable d) {
            sink.add(d);
            return;
        }
        if (result instanceof Iterable<?> it) {
            Iterator<?> iter = it.iterator();
            while (iter.hasNext()) {
                if (!guard.getAsBoolean()) break;
                Object o = iter.next();
                if (o == null) continue;
                if (!(o instanceof Disposable d)) {
                    throw new IllegalArgumentException("Invalid effect: expected Disposable, got " + o.getClass());
                }
                sink.add(d);
            }
            return;
        }
        throw new IllegalArgumentException("Invalid effect: " + result.getClass());
    }

    private static void runReverse(List<Disposable> disposables) {
        for (int i = disposables.size() - 1; i >= 0; i--) {
            disposables.get(i).dispose();
        }
    }

    /** 当前登记的所有效果(诊断用)。 */
    public List<EffectMeta> getEffects() {
        List<EffectMeta> out = new ArrayList<>();
        for (EffectRecord record : records) out.add(record.meta);
        return out;
    }

    // ------------------------------------------------------- dependency tracking

    /** 重新核对一个声明的依赖:提供者还 ACTIVE 且检查通过,才记入待用表。 */
    void checkImpl(String name) {
        Impl impl = ctx.getRoot().reflect().getImpl(ctx, name, true);
        if (impl == null) {
            pendingStore.remove(name);
            return;
        }
        if (impl.check != null) {
            boolean ok;
            try {
                ok = impl.check.test(impl.value);
            } catch (Throwable t) {
                impl.fiber.ctx.logger("cordis").error(t);
                pendingStore.remove(name);
                return;
            }
            if (!ok) {
                pendingStore.remove(name);
                return;
            }
        }
        pendingStore.put(name, impl);
    }

    /** 重算目标视图(依赖齐不齐、各由谁提供);目标变了就触发加载或卸载。 */
    public void refresh() {
        StringBuilder digest = new StringBuilder();
        for (String name : inject.keySet()) {
            Impl impl = pendingStore.get(name);
            if (impl == null) {
                target = INACTIVE;
                setEpoch(INACTIVE);
                return;
            }
            digest.append(':').append(impl.fiber.uid);
        }
        target = digest.toString();
        setEpoch(digest.toString());
    }

    private void setEpoch(String newEpoch) {
        String old = epoch;
        if (Objects.equals(newEpoch, old)) return;
        epoch = newEpoch;
        if (inertia != null) return; // the in-flight transition will re-check and chain
        if (!INACTIVE.equals(newEpoch) && INACTIVE.equals(old)) {
            updateState(FiberState.LOADING);
            inertia = this::reloadCycle;
        } else {
            updateState(FiberState.UNLOADING);
            inertia = this::unloadCycle;
        }
        runChain();
    }

    /** 把链式转换一口气跑完,直到 fiber 稳定下来(惰性语义)。 */
    private void runChain() {
        while (inertia != null) {
            Runnable task = inertia;
            inertia = null;
            task.run();
        }
    }

    /** Load: commit the resolved view, resolve config, run the plugin body, then settle or chain (Algorithm 5, reload). */
    private void reloadCycle() {
        store = new LinkedHashMap<>(pendingStore);
        String oldEpoch = epoch;
        String target0 = target;
        try {
            if (Objects.equals(epoch, oldEpoch)) {
                config = resolveConfig(rawConfig);
                EffectMeta bodyMeta = new EffectMeta(name() + ".apply");
                metaStack.push(bodyMeta);
                try {
                    Object result = plugin.apply(ctx, config);
                    List<Disposable> local = new ArrayList<>();
                    collectResult(result, local, () -> Objects.equals(target, target0));
                    for (Disposable d : local) records.add(new EffectRecord(d, bodyMeta));
                } finally {
                    metaStack.pop();
                }
                error = null;
            }
        } catch (Throwable t) {
            ctx.logger(name()).error(t);
            error = t;
            epoch = INACTIVE;
        }
        if (Objects.equals(epoch, oldEpoch)) {
            updateState(null);
            inertia = null;
        } else {
            updateState(FiberState.UNLOADING);
            inertia = this::unloadCycle;
        }
    }

    /** Unload: run every accumulated inverse in LIFO order, then settle or chain (Algorithm 5, unload). */
    private void unloadCycle() {
        // Dependents were already drained: the ACTIVE→UNLOADING state change
        // notified them, and their unload chains ran synchronously (the guard
        // on L-Unload; cordis awaits notified dependents here instead).
        List<EffectRecord> pending = new ArrayList<>(records);
        records.clear();
        for (int i = pending.size() - 1; i >= 0; i--) {
            try {
                pending.get(i).disposable.dispose();
            } catch (Throwable t) {
                ctx.logger(name()).error(t);
            }
        }
        store = null;
        if (INACTIVE.equals(epoch)) {
            updateState(null);
            inertia = null;
        } else {
            updateState(FiberState.LOADING);
            inertia = this::reloadCycle;
        }
    }

    private FiberState computeState() {
        if (uid == null) return FiberState.DISPOSED;
        if (error != null) return FiberState.FAILED;
        return INACTIVE.equals(epoch) ? FiberState.PENDING : FiberState.ACTIVE;
    }

    private void updateState(FiberState override) {
        FiberState old = state;
        FiberState next = override != null ? override : computeState();
        state = next;
        if (old == next) return;
        ctx.getRoot().events().emit(null, "internal/status", this, old);
        // only notify changes between ACTIVE and NON-ACTIVE states
        if ((old == FiberState.ACTIVE) == (next == FiberState.ACTIVE)) return;
        List<String> names = new ArrayList<>();
        for (Impl impl : List.copyOf(ctx.getRoot().reflect().store.values())) {
            if (impl.fiber == this) names.add(impl.name);
        }
        if (!names.isEmpty()) ctx.getRoot().reflect().notify(ctx, names);
    }

    // ------------------------------------------------------------------ config

    Object resolveConfig(Object raw) {
        Object cfg = ctx.getRoot().events().waterfall(null, "internal/config", raw, (Supplier<Object>) () -> raw);
        ConfigSchema<?> schema = runtime != null ? plugin.schema() : null;
        if (schema != null) {
            @SuppressWarnings("unchecked")
            ConfigSchema<Object> typed = (ConfigSchema<Object>) schema;
            return typed.validate(cfg);
        }
        return cfg;
    }

    /** 原始配置,下次激活时使用。 */
    public Object getRawConfig() {
        return rawConfig;
    }

    /** 当前生效的配置。 */
    public Object getEffectiveConfig() {
        return config;
    }

    // ------------------------------------------------------------------ public ops

    /**
     * 等生命周期稳定;若处于 FAILED(启动出错)就把错误抛出来。
     */
    public Fiber await() {
        if (error != null) {
            throw error instanceof RuntimeException re ? re : new RuntimeException(error);
        }
        return this;
    }

    /** 用当前配置把插件卸了马上重装一遍。 */
    public Fiber restart() {
        assertActive();
        setEpoch(INACTIVE);
        refresh();
        return await();
    }

    /**
     * 换新配置:先校验(不合法直接抛错,保持旧配置继续运行),通过后重启插件。
     */
    public void update(Object newConfig) {
        assertActive();
        this.rawConfig = newConfig;
        if (state != FiberState.ACTIVE) {
            // Config resolution may access injected services: defer until activation.
            error = null;
            setEpoch(INACTIVE);
            refresh();
            return;
        }
        Object cfg = resolveConfig(newConfig);
        ctx.getRoot().events().waterfall(null, "internal/update", cfg, Boolean.FALSE, (Supplier<Object>) () -> {
            this.config = cfg;
            this.error = null;
            restart();
            return null;
        });
    }

    /** 退役:卸载插件并从注册表移除;挂在父 fiber 上,父卸载时会被连带卸载。 */
    public void dispose() {
        if (retireHandle != null) {
            retireHandle.dispose();
        } else {
            // root fiber: dispose the whole application
            ctx.getRoot().registry().deleteAll();
        }
    }

    /** 由本 fiber 提供一个服务;“注销绑定”也做成撤销动作,卸载时自动生效。 */
    Disposable provide(Context from, String name, Object value, Predicate<Object> check) {
        ReflectService reflect = ctx.getRoot().reflect();
        return effect(cb -> {
            reflect.globalRealm(name); // ensure a default realm exists for global resolvers
            RealmKey key = from.resolveRealm(name);
            Impl existing = reflect.store.get(key);
            if (existing != null) {
                throw new CordisException(CordisException.Code.DUPLICATE_PROVIDE,
                        "service \"" + name + "\" has been registered at <" + existing.fiber.name() + ">");
            }
            Impl impl = new Impl(name, this, value, check);
            reflect.store.put(key, impl);
            if (store != null) store.put(name, impl);
            if (state == FiberState.ACTIVE) reflect.notify(from, List.of(name));
            return (Disposable) () -> {
                reflect.store.remove(key);
                reflect.notify(from, List.of(name));
                if (store != null) store.remove(name);
            };
        }, "ctx.provide(\"" + name + "\")");
    }

    @Override
    public String toString() {
        return "Fiber<" + name() + "#" + uid + ":" + state + ">";
    }
}
