package dev.cordis;

import dev.cordis.core.CordisException;
import dev.cordis.core.effect.Disposable;
import dev.cordis.core.runtime.Fiber;
import dev.cordis.core.runtime.FiberState;
import dev.cordis.core.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 响应式余效应:依赖齐了就激活,缺了就卸载。 */
class LifecycleTest {

    /** 提供者插件:提供 svc 服务,值为给定内容;可选记录卸载日志。 */
    static Plugin provider(String value, List<String> log) {
        return new Plugin() {
            @Override
            public String name() {
                return "provider";
            }

            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                if (log != null) log.add("provider-up");
                Disposable d = ctx.provide("svc", value);
                return (Disposable) () -> {
                    d.dispose();
                    if (log != null) log.add("provider-down");
                };
            }
        };
    }

    /** 消费者插件:声明依赖 svc。 */
    static Plugin consumer(List<String> log) {
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
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                String svc = ctx.get("svc");
                log.add("consumer-up:" + svc);
                return (Disposable) () -> log.add("consumer-down");
            }
        };
    }

    @Test
    void consumerWaitsThenActivatesOnProvide() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber consumerFiber = root.plugin(consumer(log));
        assertEquals(FiberState.PENDING, consumerFiber.state);
        assertTrue(log.isEmpty());

        root.plugin(provider("v1", log)).await();
        assertEquals(FiberState.ACTIVE, consumerFiber.state);
        assertEquals(List.of("provider-up", "consumer-up:v1"), log);
    }

    @Test
    void withdrawingProviderDeactivatesThenReprovideReactivates() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber consumerFiber = root.plugin(consumer(log));
        Fiber providerFiber = root.plugin(provider("v1", log)).await();
        assertEquals(FiberState.ACTIVE, consumerFiber.state);

        log.clear();
        providerFiber.dispose();
        // guard ordering: the consumer fully unloads before the provider's own teardown
        assertEquals(List.of("consumer-down", "provider-down"), log);
        assertEquals(FiberState.PENDING, consumerFiber.state);

        log.clear();
        root.plugin(provider("v2", log)).await();
        assertEquals(FiberState.ACTIVE, consumerFiber.state);
        assertTrue(log.contains("consumer-up:v2"));
    }

    @Test
    void providerReplacementReloadsConsumerAgainstNewFiber() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        root.plugin(consumer(log));
        Fiber p1 = root.plugin(provider("v1", null)).await();
        p1.dispose();
        root.plugin(provider("v2", null)).await();
        // the consumer reloads precisely because the provider fiber identity changed
        assertEquals(List.of("consumer-up:v1", "consumer-down", "consumer-up:v2"), log);
    }

    @Test
    void duplicateProvideInSameRealmThrows() {
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        root.plugin(provider("a", null)).await();
        assertThrows(CordisException.class, () -> root.provide("svc", "b"));
    }

    @Test
    void restartUnloadsAndReloadsWithSameConfig() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber fiber = root.plugin(new Plugin() {
            @Override
            public String name() {
                return "restartable";
            }

            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                log.add("up:" + config);
                return (Disposable) () -> log.add("down");
            }
        }, "cfg-1").await();

        fiber.restart();
        assertEquals(List.of("up:cfg-1", "down", "up:cfg-1"), log);
        assertEquals(FiberState.ACTIVE, fiber.state);
    }

    @Test
    void updateAppliesNewConfigBehindTheWaterfall() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber fiber = root.plugin(new Plugin() {
            @Override
            public String name() {
                return "cfg";
            }

            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                log.add("up:" + config);
                return (Disposable) () -> log.add("down");
            }
        }, "old").await();
        fiber.update("new");
        assertEquals(List.of("up:old", "down", "up:new"), log);
        assertEquals("new", fiber.getEffectiveConfig());
    }
}
