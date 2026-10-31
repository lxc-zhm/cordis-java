package dev.cordis.core.coeffect;

import dev.cordis.core.runtime.Fiber;

import java.util.function.Predicate;

/**
 * 一条服务绑定:某服务名在某个隔离域里的具体实现。
 *
 * <p>绑定跟着提供者 fiber 走:fiber 处于 ACTIVE 时,同隔离域里的依赖方才能看到它;
 * fiber 一旦卸载,绑定随之消失,依赖方会被通知去找新的提供者。</p>
 */
public final class Impl {

    /** 服务名。 */
    public final String name;
    /** 提供者 fiber,绑定的生死跟着它。 */
    public final Fiber fiber;
    /** 当前的服务值,可用 ctx.set 更新。 */
    public Object value;
    /** 可选的“是否可用”检查:依赖方加载前会先问一次。 */
    public final Predicate<Object> check;

    public Impl(String name, Fiber fiber, Object value, Predicate<Object> check) {
        this.name = name;
        this.fiber = fiber;
        this.value = value;
        this.check = check;
    }

    @Override
    public String toString() {
        return "Impl(" + name + ", fiber=" + fiber.name() + ")";
    }
}
