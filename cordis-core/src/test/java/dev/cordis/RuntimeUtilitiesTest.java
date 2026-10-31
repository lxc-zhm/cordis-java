package dev.cordis;

import dev.cordis.core.*;
import dev.cordis.core.runtime.*;
import dev.cordis.core.coeffect.*;
import dev.cordis.core.effect.*;
import dev.cordis.loader.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RuntimeUtilitiesTest {
    @TempDir
    Path dir;

    interface Database {
        void write(String key);
    }

    @Test
    void contextualInterfaceUsesConsumerPolicyAndUpdatesWithoutRestart() {
        Context root = Context.createRoot();
        List<String> writes = new ArrayList<>();
        root.provide("db", Service.bind(Database.class, (ctx, call) -> {
            if (ctx.resolveIntercept("db") instanceof Map<?, ?> policy && Boolean.TRUE.equals(policy.get("readOnly")))
                throw new IllegalStateException("read only");
            writes.add((String) call.arguments()[0]);
            return null;
        }));
        Fiber c = root.plugin(Plugin.of("c", List.of("db"), (ctx, cfg) -> null));
        Database unrestricted = c.ctx.get("db");
        unrestricted.write("a");
        Context restricted = c.ctx.intercept("db", Map.of("readOnly", true));
        Database view = restricted.get("db");
        assertThrows(IllegalStateException.class, () -> view.write("b"));
        restricted.patchIntercept(Map.of());
        view.write("c");
        assertEquals(List.of("a", "c"), writes);
        root.fiber().dispose();
    }

    @Test
    void updateDuringAsyncStartupRunsLatestConfigurationAfterCleanup() {
        Context root = Context.createRoot();
        CompletableFuture<Disposable> setup = new CompletableFuture<>();
        List<String> log = new ArrayList<>();
        Fiber f = root.plugin(Plugin.of("p", List.of(), (ctx, cfg) -> {
            log.add("up:" + cfg);
            return cfg.equals("old") ? setup : null;
        }), "old");
        var updated = f.updateAsync("new");
        assertFalse(updated.toCompletableFuture().isDone());
        setup.complete(() -> log.add("down:old"));
        Stages.join(updated);
        assertEquals(List.of("up:old", "down:old", "up:new"), log);
        assertEquals("new", f.getEffectiveConfig());
        root.fiber().dispose();
    }

    @Test
    void loadingEntriesStartConcurrently() {
        Context root = Context.createRoot();
        CompletableFuture<Disposable> barrier = new CompletableFuture<>();
        try (ComponentLoader loader = new ComponentLoader(root, url -> Plugin.of(url, List.of(), (ctx, cfg) -> {
            if (url.equals("wait")) return barrier;
            barrier.complete(Disposable.noop());
            return null;
        }))) {
            loader.load(List.of(Entry.of("a", "wait").build(), Entry.of("b", "release").build()));
            assertEquals(FiberState.ACTIVE, loader.mounted("a").fiber.state);
        }
    }

    @Test
    void timerCallbacksAreOwnedByConsumers() throws Exception {
        Context root = Context.createRoot();
        new TimerService(root);
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger forbidden = new AtomicInteger();
        Fiber consumer = root.plugin(Plugin.of("c", List.of("timer"), (ctx, cfg) -> {
            TimerService timer = ctx.get("timer");
            timer.timeout(fired::countDown, Duration.ofMillis(10));
            timer.timeout(forbidden::incrementAndGet, Duration.ofDays(1));
            return null;
        }));
        assertTrue(fired.await(3, TimeUnit.SECONDS));
        consumer.dispose();
        assertEquals(0, forbidden.get());
        root.fiber().dispose();
    }

    @Test
    void debounceAndThrottleCombineCalls() throws Exception {
        Context root = Context.createRoot();
        TimerService timer = new TimerService(root);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        Runnable callback = () -> {
            calls.incrementAndGet();
            done.countDown();
        };
        try (var debounce = timer.debounce(callback, Duration.ofMillis(20)); var throttle = timer.throttle(callback, Duration.ofMillis(20))) {
            for (int i = 0; i < 10; i++) {
                debounce.run();
                throttle.run();
            }
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            root.fiber().dispose();
        }
    }

    @Test
    void fileWatcherReconcilesAnEditedConfiguration() throws Exception {
        Path file = dir.resolve("cordis.yaml");
        ConfigFile.write(file, List.of(Entry.of("a", "p").config("before").build()));
        Context root = Context.createRoot();
        AtomicReference<Object> seen = new AtomicReference<>();
        CountDownLatch reloaded = new CountDownLatch(1);
        root.on("loader/config-reloaded", args -> {
            reloaded.countDown();
            return null;
        });
        try (ComponentLoader loader = ConfigFile.open(root, url -> Plugin.of("p", List.of(), (ctx, cfg) -> {
            seen.set(cfg);
            return null;
        }), file);
             ConfigWatcher watcher = new ConfigWatcher(loader, file, Duration.ofMillis(100))) {
            ConfigFile.write(file, List.of(Entry.of("a", "p").config("after").build()));
            assertTrue(reloaded.await(5, TimeUnit.SECONDS));
            assertEquals("after", seen.get());
        } finally {
            root.fiber().dispose();
        }
    }
    @Test void watcherTracksIncludeAndRootInTheSameCanonicalDirectory() throws Exception {
        Path child=dir.resolve("child.yaml"),file=dir.resolve("root.yaml");
        ConfigFile.write(child,List.of(Entry.of("p","p").config("one").build()));
        ConfigFile.write(file,List.of(Entry.of("include","include").config("child.yaml").build()));
        Context root=Context.createRoot();BlockingQueue<Path> events=new LinkedBlockingQueue<>();
        root.on("loader/config-reloaded",args->{events.add((Path)args[0]);return null;});
        try(ComponentLoader loader=ConfigFile.open(root,u->Plugin.of("p",List.of(),(ctx,cfg)->{ctx.provide("value",cfg);return null;}),file);
            ConfigWatcher watcher=new ConfigWatcher(loader,file,Duration.ofMillis(100))) {
            ConfigFile.write(child,List.of(Entry.of("p","p").config("two").build()));
            assertEquals(child.toRealPath(),events.poll(5,TimeUnit.SECONDS));assertEquals("two",root.get("value"));
            ConfigFile.write(file,List.of(Entry.of("include","include").config("child.yaml").disabled(true).build()));
            assertEquals(file.toRealPath(),events.poll(5,TimeUnit.SECONDS));assertNull(root.get("value"));
        }finally{root.fiber().dispose();}
    }

}
