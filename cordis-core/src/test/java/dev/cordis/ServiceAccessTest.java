package dev.cordis;

import dev.cordis.core.CordisException;
import dev.cordis.core.Fiber;
import dev.cordis.core.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 服务访问规则:没声明不能看,没加载不能拿。 */
class ServiceAccessTest {

    static Plugin consumer() {
        return new Plugin() {
            @Override
            public String name() {
                return "consumer";
            }

            @Override
            public List<String> inject() {
                return List.of("svc");
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                assertEquals("v", ctx.get("svc"));
                return null;
            }
        };
    }

    @Test
    void undeclaredAccessFromPluginContextThrows() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        // provider is a PLUGIN fiber: its binding is not ambient authority
        root.plugin(new Plugin() {
            @Override
            public String name() {
                return "provider";
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                ctx.provide("svc", "v");
                return null;
            }
        }).await();
        Fiber fiber = root.plugin(new Plugin() {
            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                return null;
            }
        }).await();
        // the consumer never declared `svc`: access is rejected even though it is provided
        CordisException e = assertThrows(CordisException.class, () -> fiber.ctx.get("svc"));
        assertEquals(CordisException.Code.UNDECLARED_ACCESS, e.getCode());
    }

    @Test
    void declaredAccessOnUnloadedFiberThrowsInactive() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        final dev.cordis.core.Context[] captured = new dev.cordis.core.Context[1];
        Fiber fiber = root.plugin(new Plugin() {
            @Override
            public List<String> inject() {
                return List.of("svc");
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                captured[0] = ctx;
                return null;
            }
        });
        assertEquals(dev.cordis.core.FiberState.PENDING, fiber.state);
        // the fiber declares svc but has not loaded: access fails with INACTIVE_ACCESS
        dev.cordis.core.CordisException e = assertThrows(dev.cordis.core.CordisException.class,
                () -> fiber.ctx.get("svc"));
        assertEquals(CordisException.Code.INACTIVE_ACCESS, e.getCode());
        assertNotNull(captured);
    }

    @Test
    void rootAccessIsNonStrict() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        root.provide("any", 42);
        assertEquals(42, ((Number) root.get("any")).intValue());
        assertNull(root.get("missing"));
    }

    @Test
    void setRules() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        final dev.cordis.core.Context[] captured = new dev.cordis.core.Context[1];
        root.plugin(new Plugin() {
            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                captured[0] = ctx;
                ctx.provide("state", "initial");
                ctx.set("state", "updated"); // the providing fiber may set
                assertEquals("updated", ctx.get("state"));
                return null;
            }
        }).await();
        // another fiber may not set it
        assertThrows(CordisException.class, () -> root.set("state", "x"));
        assertThrows(CordisException.class, () -> root.set("never-provided", "x"));
    }
}
