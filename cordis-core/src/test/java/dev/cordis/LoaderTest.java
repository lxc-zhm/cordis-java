package dev.cordis;

import dev.cordis.core.Fiber;
import dev.cordis.core.FiberState;
import dev.cordis.core.Plugin;
import dev.cordis.core.ValidationException;
import dev.cordis.loader.ComponentLoader;
import dev.cordis.loader.Entry;
import dev.cordis.loader.PluginResolver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 声明式配置与调和:按 id 增量更新。 */
class LoaderTest {

    /** 内存里的模块注册表:url → 插件工厂。 */
    static class Registry implements PluginResolver {
        final Map<String, java.util.function.Supplier<Plugin>> modules = new HashMap<>();

        @Override
        public Plugin resolve(String url) {
            java.util.function.Supplier<Plugin> factory = modules.get(url);
            if (factory == null) throw new IllegalArgumentException("module not found: " + url);
            return factory.get();
        }
    }

    static Plugin counterPlugin(String name, AtomicInteger loads, AtomicReference<Object> seenConfig) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                loads.incrementAndGet();
                seenConfig.set(config);
                return null;
            }
        };
    }

    @Test
    void reconcileAddsRemovesAndUpdates() {
        Registry registry = new Registry();
        AtomicInteger aLoads = new AtomicInteger();
        AtomicReference<Object> aConfig = new AtomicReference<>();
        registry.modules.put("mod-a", () -> counterPlugin("a", aLoads, aConfig));
        AtomicInteger bLoads = new AtomicInteger();
        AtomicReference<Object> bConfig = new AtomicReference<>();
        registry.modules.put("mod-b", () -> counterPlugin("b", bLoads, bConfig));

        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, registry);

        loader.load(List.of(
                Entry.of("a", "mod-a").config(1).build(),
                Entry.of("b", "mod-b").build()));
        assertEquals(1, aLoads.get());
        assertEquals(1, bLoads.get());
        assertEquals(1, aConfig.get());

        // config update: no remount, fiber.update restarts the same plugin
        loader.load(List.of(
                Entry.of("a", "mod-a").config(2).build(),
                Entry.of("b", "mod-b").build()));
        assertEquals(2, aLoads.get());
        assertEquals(2, aConfig.get());
        assertEquals(1, bLoads.get()); // untouched entry is not restarted

        // removal
        loader.load(List.of(Entry.of("a", "mod-a").config(2).build()));
        assertEquals(List.of("a"), loader.ids());
    }

    @Test
    void disabledKeepsEntryUnmounted() {
        Registry registry = new Registry();
        AtomicInteger loads = new AtomicInteger();
        registry.modules.put("mod", () -> counterPlugin("m", loads, new AtomicReference<>()));

        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, registry);

        loader.load(List.of(Entry.of("m", "mod").disabled(true).build()));
        assertEquals(0, loads.get());
        assertNull(loader.mounted("m").fiber);

        loader.load(List.of(Entry.of("m", "mod").build()));
        assertEquals(1, loads.get());
        assertNotNull(loader.mounted("m").fiber);

        loader.load(List.of(Entry.of("m", "mod").disabled(true).build()));
        assertNull(loader.mounted("m").fiber);
    }

    @Test
    void urlChangeRebuilds() {
        Registry registry = new Registry();
        AtomicInteger v1Loads = new AtomicInteger();
        AtomicInteger v2Loads = new AtomicInteger();
        registry.modules.put("v1", () -> counterPlugin("v1", v1Loads, new AtomicReference<>()));
        registry.modules.put("v2", () -> counterPlugin("v2", v2Loads, new AtomicReference<>()));

        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, registry);
        loader.load(List.of(Entry.of("m", "v1").build()));
        loader.load(List.of(Entry.of("m", "v2").build()));
        assertEquals(1, v1Loads.get());
        assertEquals(1, v2Loads.get());
        assertEquals("v2", loader.mounted("m").plugin.name());
    }

    @Test
    void configSchemaFailureLeavesFiberFailed() {
        Registry registry = new Registry();
        registry.modules.put("strict", () -> new Plugin() {
            @Override
            public String name() {
                return "strict";
            }

            @Override
            public dev.cordis.core.ConfigSchema<?> schema() {
                return raw -> {
                    if (!(raw instanceof Integer i) || i > 10) {
                        throw new ValidationException(List.of(new ValidationException.Issue("must be ≤ 10")));
                    }
                    return raw;
                };
            }

            @Override
            public Object apply(dev.cordis.core.Context ctx, Object config) {
                return null;
            }
        });

        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, registry);
        loader.load(List.of(Entry.of("s", "strict").config(3).build()));
        assertEquals(FiberState.ACTIVE, loader.mounted("s").fiber.state);

        // an invalid update is rejected up-front: the loader logs it and the fiber
        // keeps running the old config
        loader.load(List.of(Entry.of("s", "strict").config(99).build()));
        assertEquals(FiberState.ACTIVE, loader.mounted("s").fiber.state);
        assertEquals(3, loader.mounted("s").fiber.getEffectiveConfig());
        assertThrows(ValidationException.class, () -> loader.mounted("s").fiber.update(99));

        // mounting with an invalid initial config lands the fiber in FAILED
        loader.load(List.of(Entry.of("s2", "strict").config(99).build()));
        assertEquals(FiberState.FAILED, loader.mounted("s2").fiber.state);
        assertThrows(ValidationException.class, () -> loader.mounted("s2").fiber.await());
    }

    @Test
    void mountUnderMissingModuleIsReportedNotThrown() {
        Registry registry = new Registry();
        dev.cordis.core.Context root = dev.cordis.core.Context.createRoot();
        ComponentLoader loader = new ComponentLoader(root, registry);
        loader.load(List.of(Entry.of("x", "missing").build()));
        assertNull(loader.mounted("x").fiber);
    }
}
