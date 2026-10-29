package dev.cordis.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 事件总线:发布/订阅消息用(挂在根上下文上)。
 *
 * <p>监听器也是一种“效果”:注册它的 fiber 卸载时会被自动移除。
 * 派发遵循冒泡规则:在上下文 A 上注册的监听器,能收到 A 及其子上下文发出的事件。</p>
 */
public final class EventsService {

    /** 监听器回调;waterfall 派发时,参数数组的最后一个元素是“下一步”回调。 */
    @FunctionalInterface
    public interface Listener {
        Object invoke(Object... args);
    }

    static final class Hook {
        final Context ctx;
        final Listener listener;

        Hook(Context ctx, Listener listener) {
            this.ctx = ctx;
            this.listener = listener;
        }
    }

    private final Context root;
    private final Map<String, List<Hook>> hooks = new HashMap<>();

    EventsService(Context root) {
        this.root = root;
    }

    /**
     * 注册事件监听器,归当前 fiber 所有(卸载时自动移除)。
     *
     * @return 一个可用来手动移除监听器的动作
     */
    public Disposable on(Context ctx, String name, Listener listener, boolean prepend) {
        ctx.fiber().assertActive();
        Hook hook = new Hook(ctx, listener);
        List<Hook> list = hooks.computeIfAbsent(name, k -> new ArrayList<>());
        return ctx.fiber().effect(cb -> {
            if (prepend) list.add(0, hook);
            else list.add(hook);
            return (Disposable) () -> list.remove(hook);
        }, "ctx.on(\"" + name + "\")");
    }

    List<Listener> dispatch(Context thisArg, String name) {
        List<Hook> list = hooks.get(name);
        if (list == null || list.isEmpty()) return List.of();
        List<Listener> out = new ArrayList<>();
        for (Hook hook : List.copyOf(list)) {
            if (accepts(hook, thisArg)) out.add(hook.listener);
        }
        return out;
    }

    /** 冒泡规则:监听器注册的上下文,是派发上下文本身或其祖先时才接收。 */
    private static boolean accepts(Hook hook, Context dispatchCtx) {
        if (dispatchCtx == null) return true;
        for (Context c = dispatchCtx; c != null; c = c.getParent()) {
            if (c == hook.ctx) return true;
        }
        return false;
    }

    /** 同步派发事件:不关心监听器返回值;监听器抛异常只记日志,不打断别人。 */
    public void emit(Context thisArg, String name, Object... args) {
        for (Listener listener : dispatch(thisArg, name)) {
            try {
                listener.invoke(args);
            } catch (Throwable t) {
                root.logger("cordis").error(t);
            }
        }
    }

    /** 顺序调用所有监听器;有人抛错也先跑完,最后把第一个错误抛出去。 */
    public void parallel(Context thisArg, String name, Object... args) {
        Throwable first = null;
        for (Listener listener : dispatch(thisArg, name)) {
            try {
                listener.invoke(args);
            } catch (Throwable t) {
                if (first == null) first = t;
            }
        }
        if (first instanceof RuntimeException re) throw re;
        if (first instanceof Error e) throw e;
        if (first != null) throw new RuntimeException(first);
    }

    /** 判断监听器返回值是否表示“到此为止”。 */
    static boolean isBailed(Object value) {
        return value != null && !Boolean.FALSE.equals(value);
    }

    /** 依次调用监听器,遇到一个“到此为止”的返回值就停;返回该值或 null。 */
    public Object bail(Context thisArg, String name, Object... args) {
        for (Listener listener : dispatch(thisArg, name)) {
            Object result = listener.invoke(args);
            if (isBailed(result)) return result;
        }
        return null;
    }

    /**
     * 洋葱式派发:最后一个参数是“最里层”的默认行为;
     * 每个监听器都会额外收到一个 next,调用 next 才会继续走后面的流程,
     * 不调用就等于否决(可用于拦截)。
     */
    public Object waterfall(Context thisArg, String name, Object... argsWithNext) {
        List<Listener> cbs = new ArrayList<>(dispatch(thisArg, name));
        Object[] args = Arrays.copyOf(argsWithNext, argsWithNext.length - 1);
        Supplier<Object> inner = () -> {
            Object last = argsWithNext[argsWithNext.length - 1];
            if (last instanceof Listener l) return l.invoke(args);
            if (last instanceof Supplier<?> s) return ((Supplier<Object>) s).get();
            return null;
        };
        return waterfallStep(new ArrayDeque<>(cbs), args, inner);
    }

    private Object waterfallStep(java.util.ArrayDeque<Listener> cbs, Object[] args, Supplier<Object> inner) {
        if (cbs.isEmpty()) return inner.get();
        Listener cb = cbs.poll();
        Object[] full = Arrays.copyOf(args, args.length + 1);
        full[args.length] = (Supplier<Object>) () -> waterfallStep(cbs, args, inner);
        return cb.invoke(full);
    }

    private static final class ArrayDeque<T> extends java.util.ArrayDeque<T> {
        ArrayDeque(java.util.Collection<? extends T> c) {
            super(c);
        }
    }
}
