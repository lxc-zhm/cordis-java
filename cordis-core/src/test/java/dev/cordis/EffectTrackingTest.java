package dev.cordis;

import dev.cordis.core.effect.Disposable;
import dev.cordis.core.effect.EffectCallback;
import dev.cordis.core.runtime.Fiber;
import dev.cordis.core.runtime.FiberState;
import dev.cordis.core.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 可回退效应:登记顺序与 LIFO 回滚。 */
class EffectTrackingTest {

    /** A plugin whose apply registers labeled effects, recording their unwind order. */
    static Plugin effectsPlugin(List<String> log, String... names) {
        return new Plugin() {
            @Override
            public String name() {
                return "effects";
            }

            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                for (String n : names) {
                    ctx.effect(cb -> {
                        log.add("open:" + n);
                        return (Disposable) () -> log.add("close:" + n);
                    }, n);
                }
                return null;
            }
        };
    }

    @Test
    void effectsUnwindInLifoOrderOnFiberUnload() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber fiber = root.plugin(effectsPlugin(log, "a", "b", "c")).await();
        assertEquals(FiberState.ACTIVE, fiber.state);
        assertEquals(List.of("open:a", "open:b", "open:c"), log);

        log.clear();
        fiber.dispose();
        assertEquals(List.of("close:c", "close:b", "close:a"), log);
        assertEquals(FiberState.DISPOSED, fiber.state);
    }

    @Test
    void explicitDisposeRunsOnce() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context ctx = dev.cordis.core.runtime.Context.createRoot();
        Disposable d = ctx.effect(cb -> {
            log.add("open");
            return (Disposable) () -> log.add("close");
        }, "e");
        d.dispose();
        d.dispose(); // second call is a no-op
        assertEquals(List.of("open", "close"), log);

        // the disposed effect no longer belongs to the fiber: unload adds nothing
        log.clear();
        ctx.fiber().dispose();
        assertTrue(log.isEmpty());
    }

    @Test
    void multiStageIterableCollectsEveryStage() {
        List<String> log = new ArrayList<>();
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber fiber = root.plugin(new Plugin() {
            @Override
            public Object apply(dev.cordis.core.runtime.Context ctx, Object config) {
                return java.util.Arrays.asList( // a lazy multi-stage effect
                        (Disposable) () -> log.add("undo-1"),
                        (Disposable) () -> log.add("undo-2"),
                        (Disposable) () -> log.add("undo-3"));
            }
        }).await();
        fiber.dispose();
        assertEquals(List.of("undo-3", "undo-2", "undo-1"), log);
    }

    @Test
    void effectOnDisposedFiberThrows() {
        dev.cordis.core.runtime.Context root = dev.cordis.core.runtime.Context.createRoot();
        Fiber fiber = root.plugin(Plugin.of("p", List.of(), (ctx, cfg) -> null)).await();
        fiber.dispose();
        assertThrows(dev.cordis.core.CordisException.class,
                () -> fiber.effect(cb -> null, "late"));
    }

    @Test
    void nullResultIsAnEffectWithNothingToUndo() {
        assertDoesNotThrow(() -> EffectCallback.flatten(null));
        dev.cordis.core.runtime.Context ctx = dev.cordis.core.runtime.Context.createRoot();
        assertDoesNotThrow(() -> ctx.effect(cb -> null, "empty"));
    }
}
