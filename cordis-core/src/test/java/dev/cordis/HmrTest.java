package dev.cordis;

import dev.cordis.core.Plugin;
import dev.cordis.hmr.HmrEngine;
import dev.cordis.hmr.ModuleGraph;
import dev.cordis.loader.ComponentLoader;
import dev.cordis.loader.Entry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 热模块替换:分类、陈旧检测、事务回滚。 */
class HmrTest {

    static ModuleGraph graph(Map<String, Set<String>> imports) {
        return url -> imports.getOrDefault(url, Set.of());
    }

    @Test
    void classifyResolvesEntangledModulesAndDeclinesPlainDependencies() {
        // impl(stashed, changed) ← wrapper (cycle: wrapper imports impl → reloaded too);
        // core is a plain dependency of the changed file → declined (picked up on reload, not replaced)
        Map<String, Set<String>> imports = new HashMap<>();
        imports.put("impl", Set.of("wrapper", "core"));
        imports.put("wrapper", Set.of("impl"));
        imports.put("core", Set.of());
        ModuleGraph g = graph(imports);

        HmrEngine.Classification c = HmrEngine.classify(Set.of("impl"), Set.of(), g);
        assertTrue(c.accepted().containsAll(Set.of("impl", "wrapper")));
        assertTrue(c.declined().contains("core"));
    }

    @Test
    void classifyAcceptsModulesInCycleWithAcceptedOnesAndDefaultsUndecided() {
        // S(stashed) → x → y → x : y never reaches an accepted module → undecided → declined
        Map<String, Set<String>> imports = new HashMap<>();
        imports.put("S", Set.of("x"));
        imports.put("x", Set.of("y"));
        imports.put("y", Set.of("x"));
        ModuleGraph g = graph(imports);

        HmrEngine.Classification c = HmrEngine.classify(Set.of("S"), Set.of(), g);
        assertTrue(c.accepted().contains("S"));
        assertFalse(c.accepted().contains("x"));
        assertTrue(c.declined().containsAll(Set.of("x", "y")));

        // a module in a cycle WITH an accepted module is accepted (x2 imports S)
        Map<String, Set<String>> imports2 = new HashMap<>();
        imports2.put("S", Set.of("x2"));
        imports2.put("x2", Set.of("S"));
        HmrEngine.Classification c2 = HmrEngine.classify(Set.of("S"), Set.of(), graph(imports2));
        assertTrue(c2.accepted().containsAll(Set.of("S", "x2")));
    }

    @Test
    void externalsAreNeverAccepted() {
        Map<String, Set<String>> imports = new HashMap<>();
        imports.put("app", Set.of("changed"));
        ModuleGraph g = graph(imports);
        HmrEngine.Classification c = HmrEngine.classify(Set.of("changed"), Set.of("app"), g);
        assertFalse(c.accepted().contains("app"));
        assertTrue(c.declined().contains("app"));
    }

    @Test
    void detectStaleFindsEntriesReachingChangedModules() {
        Map<String, Set<String>> imports = new HashMap<>();
        imports.put("entry1", Set.of("changed"));
        imports.put("entry2", Set.of("untouched"));
        ModuleGraph g = graph(imports);

        Set<String> accepted = HmrEngine.classify(Set.of("changed"), Set.of(), g).accepted();
        Set<String> stale = HmrEngine.detectStale(List.of("entry1", "entry2"), accepted, Set.of(), g);
        assertEquals(Set.of("entry1"), stale);
    }

    @Test
    void transactionalReloadRollsBackOnFailure() throws Exception {
        // module registry: greeting v1 active; the "edited" module throws on resolve
        java.util.function.Supplier<Plugin> v1 = () -> Plugin.of("greeting", List.of(), (ctx, cfg) -> {
            ctx.provide("greet-value", "v1");
            return null;
        });
        java.util.function.Supplier<Plugin> v2 = () -> Plugin.of("greeting", List.of(), (ctx, cfg) -> {
            ctx.provide("greet-value", "v2");
            return null;
        });
        java.util.function.Supplier<Plugin> broken = () -> {
            throw new RuntimeException("syntax error");
        };

        Map<String, java.util.function.Supplier<Plugin>> modules = new HashMap<>();
        modules.put("greeting", v1);
        AtomicInteger loads = new AtomicInteger();
        modules.put("consumer", () -> new Plugin() {
            @Override
            public List<String> inject() {
                return List.of("greet-value");
            }

            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                loads.incrementAndGet();
                return null;
            }
        });

        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, url -> modules.get(url).get());
        loader.load(List.of(
                Entry.of("greeting", "greeting").build(),
                Entry.of("consumer", "consumer").build()));
        assertEquals("v1", root.get("greet-value"));

        // hot-swap to v2: only the greeting fiber is replaced
        modules.put("greeting", v2);
        Map<String, Plugin> fresh = Map.of("greeting", v2.get());
        HmrEngine.reload(loader, List.of("greeting"), fresh, null);
        assertEquals("v2", root.get("greet-value"));
        // the consumer reloads too — its dependency is now provided by a new fiber
        // (cordis identifies bindings by provider identity, not value)
        assertTrue(loads.get() >= 2);

        // a broken module fails the reload and rolls back to the previous component
        modules.put("greeting", broken);
        assertThrows(RuntimeException.class, () ->
                HmrEngine.reload(loader, List.of("greeting"), Map.of(), null));
        assertEquals("v2", root.get("greet-value")); // v2 still serving after rollback
    }
}
