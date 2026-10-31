package dev.cordis;

import dev.cordis.core.*;
import dev.cordis.core.effect.*;
import dev.cordis.core.runtime.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AsyncLifecycleTest {
    @Test
    void waitsForAsyncSetupAndTeardown() {
        Context root = Context.createRoot();
        CompletableFuture<Disposable> setup = new CompletableFuture<>();
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        Fiber f = root.plugin(Plugin.of("async", List.of(), (c, cfg) -> setup));
        assertEquals(FiberState.LOADING, f.state);
        assertFalse(f.awaitAsync().toCompletableFuture().isDone());
        setup.complete((AsyncDisposable) () -> cleanup);
        f.await();
        assertEquals(FiberState.ACTIVE, f.state);
        var disposed = f.disposeAsync();
        assertEquals(FiberState.UNLOADING, f.state);
        assertFalse(disposed.toCompletableFuture().isDone());
        cleanup.complete(null);
        Stages.join(disposed);
        assertEquals(FiberState.DISPOSED, f.state);
        assertEquals(0, root.registry().size());
    }

    @Test
    void providerWaitsForConsumerAsyncCleanupWithCommittedView() {
        Context root = Context.createRoot();
        List<String> log = new ArrayList<>();
        CompletableFuture<Void> drain = new CompletableFuture<>();
        Fiber p = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> {
            c.provide("db", "live");
            return (Disposable) () -> log.add("provider");
        }));
        Fiber consumer = root.plugin(Plugin.of("c", List.of("db"), (c, cfg) -> (AsyncDisposable) () -> {
            assertEquals("live", c.get("db"));
            log.add("consumer-start");
            return drain.thenRun(() -> {
                assertEquals("live", c.get("db"));
                log.add("consumer-end");
            });
        }));
        var done = p.disposeAsync();
        assertEquals(List.of("consumer-start"), log);
        assertFalse(done.toCompletableFuture().isDone());
        drain.complete(null);
        Stages.join(done);
        assertEquals(List.of("consumer-start", "consumer-end", "provider"), log);
        assertEquals(FiberState.PENDING, consumer.state);
    }

    @Test
    void partiallyYieldedStartupIsRecoveredOnFailure() {
        Context root = Context.createRoot();
        AtomicInteger count = new AtomicInteger();
        Fiber f = root.plugin(Plugin.of("bad", List.of(), (c, cfg) -> (Iterable<Disposable>) () -> new Iterator<>() {
            int i;

            public boolean hasNext() {
                return true;
            }

            public Disposable next() {
                if (i++ == 0) {
                    count.incrementAndGet();
                    return count::decrementAndGet;
                }
                throw new IllegalStateException("failed");
            }
        }));
        assertThrows(IllegalStateException.class, f::await);
        assertEquals(0, count.get());
        assertEquals(FiberState.FAILED, f.state);
    }

    @Test
    void rootDisposalRemovesEffectsServicesAndGlobalListeners() {
        Context root = Context.createRoot();
        AtomicInteger n = new AtomicInteger();
        root.effect(c -> (Disposable) n::incrementAndGet);
        root.provide("ambient", 1);
        Fiber owner = root.plugin(Plugin.of("owner", List.of(), (c, cfg) -> {
            c.onGlobal("x", args -> n.incrementAndGet());
            return null;
        }));
        owner.dispose();
        root.emit("x");
        assertEquals(0, n.get());
        root.fiber().dispose();
        assertEquals(1, n.get());
        assertNull(root.get("ambient"));
        assertTrue(root.fiber().getEffects().isEmpty());
        assertThrows(CordisException.class, () -> root.effect(c -> null));
    }

    @Test
    void iteratorStopsBeforeAdvancingStaleNextStep() {
        Context root = Context.createRoot();
        AtomicInteger allocations = new AtomicInteger();
        Fiber p = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> {
            c.provide("db", 1);
            return null;
        }));
        Fiber c = root.plugin(Plugin.of("c", List.of("db"), (ctx, cfg) -> (Iterable<Disposable>) () -> new Iterator<>() {
            int stage;

            public boolean hasNext() {
                return stage < 2;
            }

            public Disposable next() {
                stage++;
                allocations.incrementAndGet();
                if (stage == 1) p.dispose();
                return allocations::decrementAndGet;
            }
        }));
        assertEquals(0, allocations.get());
        assertEquals(FiberState.PENDING, c.state);
    }

    @Test
    void asyncIteratorRetainsLateInverseThenStops() {
        Context root = Context.createRoot();
        AtomicInteger count = new AtomicInteger();
        CompletableFuture<AsyncEffect.Step> next = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        Fiber p = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> {
            c.provide("db", 1);
            return null;
        }));
        Fiber c = root.plugin(Plugin.of("c", List.of("db"), (ctx, cfg) -> (AsyncEffect) () -> {
            calls.incrementAndGet();
            return next;
        }));
        var done = p.disposeAsync();
        assertFalse(done.toCompletableFuture().isDone());
        count.incrementAndGet();
        next.complete(AsyncEffect.Step.yield(count::decrementAndGet));
        Stages.join(done);
        assertEquals(0, count.get());
        assertEquals(1, calls.get());
        assertEquals(FiberState.PENDING, c.state);
    }

    @Test
    void effectVisibleDuringReentrantOwnerDisposal() {
        Context root = Context.createRoot();
        AtomicInteger n = new AtomicInteger();
        Fiber owner = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> null));
        owner.ctx.effect(c -> {
            owner.dispose();
            n.incrementAndGet();
            return (Disposable) n::decrementAndGet;
        });
        assertEquals(0, n.get());
        assertEquals(FiberState.DISPOSED, owner.state);
    }

    @Test
    void allInversesRunEvenWhenOneThrows() {
        Context root = Context.createRoot();
        List<Integer> log = new ArrayList<>();
        Fiber f = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> List.of(
                (Disposable) () -> log.add(1), (Disposable) () -> {
                    throw new IllegalStateException("cleanup");
                }, (Disposable) () -> log.add(3))));
        f.dispose();
        assertEquals(List.of(3, 1), log);
    }

    @Test
    void explicitAsyncEffectDelaysActivation() {
        Context root = Context.createRoot();
        CompletableFuture<Disposable> setup = new CompletableFuture<>();
        Fiber f = root.plugin(Plugin.of("p", List.of(), (c, cfg) -> {
            c.effect(x -> setup);
            return null;
        }));
        assertEquals(FiberState.LOADING, f.state);
        setup.complete(Disposable.noop());
        f.await();
        assertEquals(FiberState.ACTIVE, f.state);
    }

    @Test
    void parallelEventWaitsForAllListeners() {
        Context root = Context.createRoot();
        var a = new CompletableFuture<Void>();
        var b = new CompletableFuture<Void>();
        root.on("event", args -> a);
        root.on("event", args -> b);
        var done = root.events().parallelAsync(root, "event").toCompletableFuture();
        a.completeExceptionally(new IllegalStateException("a"));
        assertFalse(done.isDone());
        b.complete(null);
        assertThrows(CompletionException.class, done::join);
    }
    @Test void setupCompletionOnAnotherExecutorPublishesBeforeAwaitReturns() {
        Context root=Context.createRoot();CompletableFuture<Disposable> setup=new CompletableFuture<>();
        Fiber fiber=root.plugin(Plugin.of("p",List.of(),(ctx,cfg)->setup));
        Thread.ofVirtual().start(()->setup.complete(Disposable.noop()));
        fiber.await();assertEquals(FiberState.ACTIVE,fiber.state);root.fiber().dispose();
    }

}
