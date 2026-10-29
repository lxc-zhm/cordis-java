package dev.cordis;

import dev.cordis.core.Fiber;
import dev.cordis.core.FiberState;
import dev.cordis.core.Plugin;
import dev.cordis.core.RealmKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 服务隔离与拦截:同名服务在不同域各自独立;拦截配置可收缩权限。 */
class IsolationTest {

    static Plugin provider(String value) {
        return Plugin.of("provider", List.of(), (ctx, cfg) -> {
            ctx.provide("svc", value);
            return null;
        });
    }

    static Plugin consumer(List<String> log) {
        return new Plugin() {
            @Override
            public List<String> inject() {
                return List.of("svc");
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                log.add("see:" + ctx.get("svc"));
                return null;
            }
        };
    }

    @Test
    void isolatedScopesResolveIndependently() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        dev.cordis.core.Context a = root.isolate("svc", "scope-a");
        dev.cordis.core.Context b = root.isolate("svc", "scope-b");
        a.plugin(provider("A")).await();
        b.plugin(provider("B")).await();
        root.plugin(provider("root")).await();
        assertEquals("A", a.get("svc"));
        assertEquals("B", b.get("svc"));
        assertEquals("root", root.get("svc"));
    }

    @Test
    void notifyDoesNotCrossRealms() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        List<String> logA = new java.util.ArrayList<>();
        List<String> logB = new java.util.ArrayList<>();
        dev.cordis.core.Context a = root.isolate("svc", "scope-a");
        dev.cordis.core.Context b = root.isolate("svc", "scope-b");
        a.plugin(consumer(logA));
        b.plugin(consumer(logB));
        assertEquals(List.of(), logA);
        assertEquals(List.of(), logB);

        a.plugin(provider("A2")).await();
        // only scope-a's consumer resolves the new provider
        assertEquals(List.of("see:A2"), logA);
        assertEquals(List.of(), logB);

        b.plugin(provider("B2")).await();
        assertEquals(List.of("see:B2"), logB);
    }

    @Test
    void defaultIsolateCreatesFreshScopes() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        dev.cordis.core.Context a = root.isolate("svc");
        dev.cordis.core.Context b = root.isolate("svc");
        a.plugin(provider("A")).await();
        b.plugin(provider("B")).await();
        assertEquals("A", a.get("svc"));
        assertEquals("B", b.get("svc"));
        assertNull(root.getOrNull("svc"));
    }

    @Test
    void sameLabelJoinsScopes() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        RealmKey joined = RealmKey.shared("tenant-1");
        dev.cordis.core.Context a = root.isolate("svc", joined);
        dev.cordis.core.Context b = root.isolate("svc", joined);
        // a and b share the realm: one provider satisfies both
        b.plugin(provider("shared")).await();
        Fiber consumer = a.plugin(consumer(new java.util.ArrayList<>()));
        assertEquals(FiberState.ACTIVE, consumer.state);
        assertSame(a.resolveRealm("svc"), b.resolveRealm("svc"));
    }

    @Test
    void interceptMergesWithDescendantsWinning() {
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        dev.cordis.core.Context middle = root.intercept("svc", java.util.Map.of("a", 1, "b", 1));
        dev.cordis.core.Context leaf = middle.intercept("svc", java.util.Map.of("b", 2));
        assertEquals(java.util.Map.of("a", 1, "b", 2), leaf.resolveIntercept("svc"));
        assertNull(root.resolveIntercept("svc"));
    }
}
