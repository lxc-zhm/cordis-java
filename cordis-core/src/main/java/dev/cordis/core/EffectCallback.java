package dev.cordis.core;

import java.util.List;

/**
 * 效果体:ctx.effect(...) 和插件 apply 里要执行的回调。
 *
 * <p>回调可以返回:</p>
 * <ul>
 *   <li>一个 {@link Disposable} —— 单阶段效果:返回“以后怎么撤销”;</li>
 *   <li>{@code null} —— 没有需要撤销的东西;</li>
 *   <li>一组 Disposable(Iterable)—— 多阶段效果:每产生一个就登记一个;
 *       阶段之间是“检查点”,fiber 的目标变了可以在这里停下,只回滚已完成的部分。</li>
 * </ul>
 */
@FunctionalInterface
public interface EffectCallback {

    /**
     * 执行效果体,返回“以后怎么撤销”。
     *
     * @param ctx 效果运行所在的上下文
     * @return Disposable、Iterable&lt;Disposable&gt;,或 null
     */
    Object execute(Context ctx);

    /** 把回调返回值整理成撤销动作列表,便于统一处理。 */
    static List<Disposable> flatten(Object result) {
        if (result == null) return List.of();
        if (result instanceof Disposable d) return List.of(d);
        if (result instanceof Iterable<?> it) {
            List<Disposable> out = new java.util.ArrayList<>();
            for (Object o : it) {
                if (o == null) continue;
                if (!(o instanceof Disposable d)) {
                    throw new IllegalArgumentException("Invalid effect: expected Disposable, got " + o.getClass());
                }
                out.add(d);
            }
            return out;
        }
        throw new IllegalArgumentException("Invalid effect: expected Disposable or Iterable<Disposable>, got " + result.getClass());
    }
}
