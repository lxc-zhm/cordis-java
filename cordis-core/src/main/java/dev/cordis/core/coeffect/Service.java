package dev.cordis.core.coeffect;

import dev.cordis.core.runtime.Context;

import java.lang.reflect.*;
import java.util.function.BiFunction;

/**
 * A service owns shared state. Contextual views are explicit in Java (no class Proxy).
 */
public abstract class Service {
    protected final Context ctx;
    public final String name;

    protected Service(Context ctx, String name) {
        this(ctx, name, true);
    }

    /**
     * Use register=false for a view backed by an already registered service.
     */
    protected Service(Context ctx, String name, boolean register) {
        this.ctx = ctx;
        this.name = name;
        if (register) ctx.provide(name, this, value -> ((Service) value).isAvailable());
    }

    protected boolean isAvailable() {
        return true;
    }

    public Object interceptConfig() {
        return ctx.resolveIntercept(name);
    }

    /**
     * Register a contextual interface without a handwritten view class.
     */
    public static <T> ContextualService<T> bind(Class<T> api, BiFunction<Context, MethodCall, Object> invoke) {
        if (!api.isInterface()) throw new IllegalArgumentException("Service API must be an interface");
        return context -> api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                default -> "ServiceView<" + api.getName() + ">";
            };
            return invoke.apply(context, new MethodCall(method, args == null ? new Object[0] : args));
        }));
    }

    public record MethodCall(Method method, Object[] arguments) {
    }
}
