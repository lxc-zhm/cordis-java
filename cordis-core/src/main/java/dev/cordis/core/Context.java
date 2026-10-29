package dev.cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 上下文(ctx):组件与外界打交道的唯一通道。
 *
 * <p>组件的一切操作都经它:登记效果(effect)、提供/获取服务(provide/get)、
 * 加载插件(plugin)。</p>
 *
 * <p>三种派生操作生成子上下文,且都不会改动父级:</p>
 * <ul>
 *   <li>{@link #extend} —— 普通子上下文;</li>
 *   <li>{@link #isolate} —— 让某服务名在子树里解析到独立的绑定(多租户);</li>
 *   <li>{@link #intercept} —— 给某服务追加一层配置(比如只读),不改提供者和消费者。</li>
 * </ul>
 *
 * <p>get 的规则(能力检查):沿 fiber 链找第一个“加载时拿到过该服务”的祖先;
 * 声明了但没加载 → INACTIVE_ACCESS 异常;压根没声明 → UNDECLARED_ACCESS 异常。
 * 也就是说:你能用什么服务,在声明 inject 的那一刻就定死了。</p>
 */
public final class Context {

    private final Context parent;                       // null for root
    private final Map<String, RealmKey> isolateMap = new LinkedHashMap<>();
    private final Map<String, Object> interceptMap = new LinkedHashMap<>();
    private final Context rootRef;

    private Fiber fiber;
    private ReflectService reflect;   // root only
    private RegistryService registry; // root only
    private EventsService events;     // root only
    private LoggerService logger;     // root only

    private Context(Context parent) {
        this.parent = parent;
        this.rootRef = parent == null ? this : parent.rootRef;
    }

    /** 创建根上下文:内置各核心服务和根 fiber,应用的入口。 */
    public static Context createRoot() {
        Context root = new Context(null);
        root.reflect = new ReflectService(root);
        root.registry = new RegistryService(root);
        root.events = new EventsService(root);
        root.logger = new LoggerService();
        root.fiber = new Fiber(root, null, null, null);
        return root;
    }

    // ---------------------------------------------------------------- structure

    public Context getParent() {
        return parent;
    }

    public Context getRoot() {
        return rootRef;
    }

    /** 本上下文所属的 fiber:自己的,没有就往父级找。 */
    public Fiber fiber() {
        for (Context c = this; c != null; c = c.parent) {
            if (c.fiber != null) return c.fiber;
        }
        throw new IllegalStateException("no fiber on context chain");
    }

    boolean ownsFiberDirectly() {
        return fiber != null;
    }

    Context deriveChild(Fiber owner) {
        Context child = new Context(this);
        child.fiber = owner;
        return child;
    }

    /** 直接在本上下文安装一条拦截配置(fiber 构造时用它装 inject 自带的配置)。 */
    void interceptSelf(String name, Object config) {
        interceptMap.put(name, config);
    }

    /**
     * 创建子上下文:服务解析先看自己、再看父级;子级装的东西会盖住父级同名的。
     */
    public Context extend() {
        return new Context(this);
    }

    /**
     * 创建子上下文,并让 name 这个服务在子树里使用独立的绑定:
     * 同名服务在不同 isolate 分支下互不影响(多租户、测试沙箱常用)。
     *
     * @param label 隔离域标识:传相同的值(如相同字符串)表示共用同一个域;
     *              null 表示新建一个独享域
     */
    public Context isolate(String name, Object label) {
        RealmKey key = label instanceof RealmKey rk ? rk
                : label == null ? RealmKey.fresh(name)
                : RealmKey.shared(label);
        Context child = new Context(this);
        child.isolateMap.put(name, key);
        return child;
    }

    /** 同上,但新建一个独享域。 */
    public Context isolate(String name) {
        return isolate(name, null);
    }

    /**
     * 给子树里的插件追加某服务的附加配置;子级配置盖过父级。
     * 典型用途:不改提供者和消费者,把 db 收缩成只读。
     */
    public Context intercept(String name, Object config) {
        Context child = new Context(this);
        child.interceptMap.put(name, config);
        return child;
    }

    /** 合并某服务的拦截配置:从根往叶子合并,叶子优先。 */
    public Object resolveIntercept(String name) {
        List<Object> configs = new ArrayList<>();
        for (Context c = this; c != null; c = c.parent) {
            Object v = c.interceptMap.get(name);
            if (v != null) configs.add(v);
        }
        Collections.reverse(configs);
        Object merged = null;
        for (Object v : configs) {
            if (merged instanceof Map<?, ?> && v instanceof Map<?, ?>) {
                Map<Object, Object> m = merged instanceof java.util.LinkedHashMap<?, ?> lm
                        ? castMap(lm)
                        : new LinkedHashMap<>();
                if (m.isEmpty() && !(merged instanceof java.util.LinkedHashMap<?, ?>)) m.putAll((Map<?, ?>) merged);
                m.putAll((Map<?, ?>) v);
                merged = m;
            } else {
                merged = v;
            }
        }
        return merged;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Object> castMap(Map<?, ?> m) {
        return (Map<Object, Object>) m;
    }

    /** 本上下文解析 name 服务时落在哪个隔离域。 */
    public RealmKey resolveRealm(String name) {
        for (Context c = this; c != null; c = c.parent) {
            RealmKey key = c.isolateMap.get(name);
            if (key != null) return key;
        }
        return rootRef.reflect.globalRealm(name);
    }

    // ------------------------------------------------------------------ services

    /**
     * 读服务:沿 fiber 链找第一个拿得到该服务的祖先(同时做能力检查)。
     *
     * @throws CordisException 声明了但提供者未加载(INACTIVE_ACCESS),
     *                         或从未声明过(UNDECLARED_ACCESS)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Fiber f = fiber();
        if (f.runtime == null) {
            // root access: lookup against the store without the inject requirement
            Impl impl = rootRef.reflect.getImpl(this, name, false);
            return impl == null ? null : (T) impl.value;
        }
        RealmKey key = resolveRealm(name);
        while (true) {
            if (f.store != null) {
                Impl impl = f.store.get(name);
                if (impl != null) return (T) impl.value;
            }
            if (f.inject.containsKey(name)) {
                throw new CordisException(CordisException.Code.INACTIVE_ACCESS,
                        "cannot get required service \"" + name + "\" in inactive context");
            }
            if (f.runtime == null) {
                throw new CordisException(CordisException.Code.UNDECLARED_ACCESS,
                        "cannot get property \"" + name + "\" without inject");
            }
            Fiber parentFiber = f.parentCtx.fiber();
            if (parentFiber == null || f.parentCtx.resolveRealm(name) != key) {
                throw new CordisException(CordisException.Code.UNDECLARED_ACCESS,
                        "cannot get property \"" + name + "\" without inject (realm boundary)");
            }
            f = parentFiber;
        }
    }

    /** 不做声明检查地读服务;没有(或提供者未就绪)返回 null。 */
    public Object getOrNull(String name) {
        Impl impl = rootRef.reflect.getImpl(this, name, false);
        return impl == null ? null : impl.value;
    }

    /**
     * 更新已提供服务的值;只有提供者 fiber 自己能改,没提供过的名字会抛异常。
     */
    public void set(String name, Object value) {
        RealmKey key = resolveRealm(name);
        Impl impl = rootRef.reflect.store.get(key);
        if (impl == null) {
            throw new CordisException(CordisException.Code.INVALID_SET,
                    "cannot set property \"" + name + "\" without provide");
        }
        if (impl.fiber != fiber()) {
            throw new CordisException(CordisException.Code.INVALID_SET,
                    "cannot set property \"" + name + "\" in multiple fibers");
        }
        impl.value = value;
    }

    /** 由当前 fiber 提供一个服务(fiber 卸载时自动注销)。 */
    public Disposable provide(String name, Object value) {
        return provide(name, value, null);
    }

    /** 提供服务并附带“是否可用”检查:依赖方加载前会先问一次。 */
    public Disposable provide(String name, Object value, Predicate<Object> check) {
        return fiber().provide(this, name, value, check);
    }

    // ------------------------------------------------------- effects & lifecycle

    /** 登记一个可撤销的效果(详见 Fiber.effect)。 */
    public Disposable effect(EffectCallback callback) {
        return effect(callback, "anonymous");
    }

    /** 同上,附带诊断名。 */
    public Disposable effect(EffectCallback callback, String label) {
        return fiber().effect(callback, label);
    }

    /** 在本上下文加载一个插件;依赖齐了自动启动,不齐就先等着。 */
    public Fiber plugin(Plugin plugin) {
        return plugin(plugin, null);
    }

    /** 加载插件并附带配置(会先过插件的配置校验器)。 */
    public Fiber plugin(Plugin plugin, Object config) {
        return registry().plugin(this, plugin, config);
    }

    /** 简写:等依赖齐了就执行回调,依赖换了自动重跑。 */
    public Fiber inject(List<String> deps, java.util.function.BiFunction<Context, Object, Object> body) {
        return registry().inject(this, deps, body);
    }

    // ------------------------------------------------------------------ events

    /** 注册事件监听器(当前 fiber 卸载时自动移除)。 */
    public Disposable on(String name, EventsService.Listener listener) {
        return events().on(this, name, listener, false);
    }

    /** 注册全局监听器:任何上下文发出的事件都收得到。 */
    public Disposable onGlobal(String name, EventsService.Listener listener) {
        return events().on(getRoot(), name, listener, false);
    }

    /** 从本上下文派发事件(会冒泡给祖先上的监听器)。 */
    public void emit(String name, Object... args) {
        events().emit(this, name, args);
    }

    // ---------------------------------------------------------------- built-ins

    public ReflectService reflect() {
        return rootRef.reflect;
    }

    public RegistryService registry() {
        return rootRef.registry;
    }

    public EventsService events() {
        return rootRef.events;
    }

    public LoggerService loggerService() {
        return rootRef.logger;
    }

    /** 取指定名字的日志器。 */
    public Logger logger(String name) {
        return rootRef.logger.logger(name);
    }

    @Override
    public String toString() {
        return "Context<" + fiber().name() + ">";
    }
}
