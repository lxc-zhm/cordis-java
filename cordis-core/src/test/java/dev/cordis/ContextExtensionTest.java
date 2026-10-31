package dev.cordis;

import dev.cordis.core.CordisException;
import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.core.runtime.Fiber;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContextExtensionTest {
    public interface Counter { String label(String name); }
    public interface Gate { boolean available(); }

    @Test
    void availabilityUsesTheConsumersInjectionMetadata() {
        Context root = Context.createRoot();
        root.provide("gate", dev.cordis.core.coeffect.Service.bind(Gate.class, (ctx, call) ->
                ctx.resolveIntercept("gate") instanceof java.util.Map<?, ?> policy && Boolean.TRUE.equals(policy.get("allow"))),
                value -> ((Gate) value).available());
        Fiber denied = root.plugin(Plugin.of("denied", List.of("gate"), (ctx, cfg) -> null));
        Fiber allowed = root.plugin(new Plugin() {
            public java.util.Map<String, Object> injections() { return java.util.Map.of("gate", java.util.Map.of("allow", true)); }
            public Object apply(Context ctx, Object cfg) { return null; }
        });
        assertEquals(dev.cordis.core.runtime.FiberState.PENDING, denied.state);
        assertEquals(dev.cordis.core.runtime.FiberState.ACTIVE, allowed.state);
        root.fiber().dispose();
    }

    @Test
    void scopedProvisionsOfOneProviderResolveByRealm() {
        Context root = Context.createRoot();
        root.plugin(Plugin.of("p", List.of(), (ctx, cfg) -> {
            Context a = ctx.isolate("svc", "a"), b = ctx.isolate("svc", "b");
            a.provide("svc", "A");
            b.provide("svc", "B");
            assertEquals("A", a.get("svc"));
            assertEquals("B", b.get("svc"));
            return null;
        })).await();
        root.fiber().dispose();
    }

    @Test
    void computedPropertiesAreOwnedAndConflictWithServices() {
        Context root = Context.createRoot();
        AtomicInteger state = new AtomicInteger(1);
        Fiber owner = root.plugin(Plugin.of("p", List.of(), (ctx, cfg) -> {
            ctx.accessor("counter", caller -> state.get(), (caller, value) -> state.set((Integer) value));
            return null;
        }));
        root.set("counter", 2);
        assertEquals(2, (int) root.<Integer>get("counter"));
        assertThrows(CordisException.class, () -> root.provide("counter", 3));
        owner.dispose();
        assertNull(root.get("counter"));
        root.fiber().dispose();
    }

    @Test
    void mixinResolvesServicesThroughCallingContext() throws Exception {
        Context root = Context.createRoot();
        root.plugin(Plugin.of("provider", List.of(), (ctx, cfg) -> {
            ctx.provide("counter", (Counter) name -> "hello " + name);
            return null;
        })).await();
        root.mixin("counter", "label", Counter.class.getMethod("label", String.class));
        Fiber allowed = root.plugin(Plugin.of("allowed", List.of("counter"), (ctx, cfg) -> null));
        Fiber denied = root.plugin(Plugin.of("denied", List.of(), (ctx, cfg) -> null));
        assertEquals("hello test", allowed.ctx.call("label", "test"));
        assertThrows(CordisException.class, () -> denied.ctx.call("label", "test"));
        root.fiber().dispose();
    }

    @Test
    void onceAndLogExportersAreOwnedEffects() {
        Context root = Context.createRoot();
        AtomicInteger events = new AtomicInteger(), logs = new AtomicInteger();
        Fiber owner = root.plugin(Plugin.of("owner", List.of(), (ctx, cfg) -> {
            ctx.exporter((time, level, name, message) -> logs.incrementAndGet());
            ctx.once("one", args -> events.incrementAndGet());
            ctx.emit("one"); ctx.emit("one");
            return null;
        }));
        assertEquals(1, events.get());
        root.logger("test").info("before");
        assertEquals(1, logs.get());
        owner.dispose();
        root.logger("test").info("after");
        assertEquals(1, logs.get());
        root.fiber().dispose();
    }
}
