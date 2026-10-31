package dev.cordis;

import dev.cordis.core.*;
import dev.cordis.core.runtime.*;
import dev.cordis.core.coeffect.*;
import dev.cordis.loader.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ConfigTreeTest {
    @TempDir
    Path dir;

    static Plugin provider(String value) {
        return Plugin.of("provider", List.of(), (ctx, cfg) -> {
            ctx.provide("svc", value);
            return null;
        });
    }

    static ComponentLoader nested(ComponentLoader loader, String id) {
        return (ComponentLoader) loader.mounted(id).fiber.attachment("loader");
    }

    @Test
    void nestedGroupReconcilesWithoutRestartingUnchangedChildren() {
        Context root = Context.createRoot();
        AtomicInteger loads = new AtomicInteger();
        Plugin p = Plugin.of("p", List.of(), (c, cfg) -> {
            loads.incrementAndGet();
            return null;
        });
        try (ComponentLoader loader = new ComponentLoader(root, BuiltinPlugins.resolver(u -> p, dir))) {
            loader.load(List.of(Entry.of("g", "group").config(List.of(Entry.of("a", "p").config(1).build())).build()));
            Fiber first = nested(loader, "g").mounted("a").fiber;
            loader.load(List.of(Entry.of("g", "group").config(List.of(Entry.of("a", "p").config(1).build(), Entry.of("b", "p").build())).build()));
            assertSame(first, nested(loader, "g").mounted("a").fiber);
            assertEquals(2, loads.get());
            assertEquals(2, loader.tree().size());
        }
        assertEquals(0, root.registry().size());
    }

    @Test
    void yamlAndJsonRoundTripIncludesAndWriteback() throws Exception {
        for (String extension : List.of("yaml", "json")) {
            Path file = dir.resolve("config." + extension);
            var entries = List.of(Entry.of("a", "p").config(Map.of("text", "quote\"\n中文", "count", 3)).build());
            ConfigFile.write(file, entries);
            assertEquals(entries.getFirst().config, ConfigFile.read(file).getFirst().config);
            Context root = Context.createRoot();
            try (ComponentLoader loader = ConfigFile.open(root, u -> Plugin.of("p", List.of(), (c, cfg) -> null), file)) {
                loader.mounted("a").fiber.update(Map.of("text", "updated"));
                assertEquals(Map.of("text", "updated"), ConfigFile.read(file).getFirst().config);
                loader.mounted("a").fiber.dispose();
                assertTrue(ConfigFile.read(file).getFirst().disabled);
            }
        }
    }

    @Test
    void includeLoadsRelativeFilesAndCascadesDisposal() throws Exception {
        Files.createDirectories(dir.resolve("nested"));
        ConfigFile.write(dir.resolve("nested/child.yaml"), List.of(Entry.of("child", "p").build()));
        ConfigFile.write(dir.resolve("root.yaml"), List.of(Entry.of("inc", "include").config("nested/child.yaml").build()));
        Context root = Context.createRoot();
        try (ComponentLoader loader = ConfigFile.open(root, u -> provider("v"), dir.resolve("root.yaml"))) {
            assertEquals("v", root.get("svc"));
            assertEquals(List.of("child"), nested(loader, "inc").ids());
        }
        assertNull(root.get("svc"));
        assertEquals(0, root.registry().size());
    }

    @Test
    void duplicateIdsAreRejectedBeforeRemovingExistingEntries() {
        Context root = Context.createRoot();
        try (ComponentLoader loader = new ComponentLoader(root, u -> provider("v"))) {
            loader.load(List.of(Entry.of("a", "p").build()));
            Fiber before = loader.mounted("a").fiber;
            assertThrows(IllegalArgumentException.class, () -> loader.load(List.of(Entry.of("b", "p").build(), Entry.of("b", "p").build())));
            assertSame(before, loader.mounted("a").fiber);
            assertEquals(FiberState.ACTIVE, before.state);
        }
    }

    @Test
    void interceptionUpdatesInPlaceWithoutMutatingParentConfiguration() {
        Context root = Context.createRoot();
        Map<String, Object> base = new LinkedHashMap<>(Map.of("limit", 10));
        Context parent = root.intercept("svc", base);
        parent.intercept("svc", Map.of("limit", 1)).resolveIntercept("svc");
        assertEquals(Map.of("limit", 10), parent.resolveIntercept("svc"));
        try (ComponentLoader loader = new ComponentLoader(root, u -> provider("v"))) {
            loader.load(List.of(Entry.of("p", "p").build()));
            Fiber before = loader.mounted("p").fiber;
            loader.load(List.of(Entry.of("p", "p").intercept("svc", Map.of("limit", 1)).build()));
            assertSame(before, loader.mounted("p").fiber);
            assertEquals(Map.of("limit", 1), before.ctx.resolveIntercept("svc"));
        }
    }

    @Test
    void realmMovePreservesProviderAndRebindsConsumers() {
        Context root = Context.createRoot();
        List<String> log = new ArrayList<>();
        Fiber a = root.isolate("svc", "a").plugin(Plugin.of("a", List.of("svc"), (c, cfg) -> {
            log.add("a+");
            return (dev.cordis.core.effect.Disposable) () -> log.add("a-");
        }));
        Fiber b = root.isolate("svc", "b").plugin(Plugin.of("b", List.of("svc"), (c, cfg) -> {
            log.add("b+");
            return null;
        }));
        try (ComponentLoader loader = new ComponentLoader(root, u -> provider("v"))) {
            loader.load(List.of(Entry.of("p", "p").isolateShared("svc", "a").build()));
            Fiber p = loader.mounted("p").fiber;
            loader.load(List.of(Entry.of("p", "p").isolateShared("svc", "b").build()));
            assertSame(p, loader.mounted("p").fiber);
            assertEquals(FiberState.PENDING, a.state);
            assertEquals(FiberState.ACTIVE, b.state);
            assertEquals(List.of("a+", "a-", "b+"), log);
        }
        root.fiber().dispose();
    }

    @Test
    void sameStringRealmDoesNotAliasDifferentServiceNames() {
        Context root = Context.createRoot();
        Context scope = root.isolate("db", "tenant").isolate("cache", "tenant");
        scope.provide("db", 1);
        scope.provide("cache", 2);
        assertEquals(1, (int) scope.<Integer>get("db"));
        assertEquals(2, (int) scope.<Integer>get("cache"));
        root.fiber().dispose();
    }

    @Test
    void safeYamlRejectsCustomObjectTags() throws Exception {
        Path file = dir.resolve("unsafe.yaml");
        Files.writeString(file, "!!java.net.URL [https://example.invalid]");
        assertThrows(RuntimeException.class, () -> ConfigFile.read(file));
    }

    @Test
    void groupChildConfigurationChangesWriteBackToRootFile() throws Exception {
        Path file = dir.resolve("tree.yaml");
        ConfigFile.write(file, List.of(Entry.of("g", "group").config(List.of(Entry.of("a", "p").config(1).build())).build()));
        try (ComponentLoader loader = ConfigFile.open(Context.createRoot(), u -> Plugin.of("p", List.of(), (c, cfg) -> null), file)) {
            nested(loader, "g").mounted("a").fiber.update(2);
            assertEquals(2, ConfigFile.entries(ConfigFile.read(file).getFirst().config).getFirst().config);
        }
    }

    @Test
    void movingBetweenGroupsPreservesFiberRealmAndTransfersCleanup() throws Exception {
        Context root = Context.createRoot();
        try (ComponentLoader loader = new ComponentLoader(root, BuiltinPlugins.resolver(u -> provider("v"), dir))) {
            loader.load(List.of(Entry.of("left", "group").config(List.of(Entry.of("p", "p").isolate("svc").build())).build(),
                    Entry.of("right", "group").config(List.of()).build()));
            ComponentLoader left = nested(loader, "left"), right = nested(loader, "right");
            Fiber original = left.mounted("p").fiber;
            RealmKey realm = original.ctx.resolveRealm("svc");
            left.move("p", right);
            assertNull(left.mounted("p"));
            assertSame(original, right.mounted("p").fiber);
            assertSame(realm, original.ctx.resolveRealm("svc"));
            loader.mounted("left").fiber.dispose();
            assertEquals(FiberState.ACTIVE, original.state);
            loader.mounted("right").fiber.dispose();
            assertEquals(FiberState.DISPOSED, original.state);
        }
        assertEquals(0, root.registry().size());
    }

    @Test
    void conflictingRealmMoveRollsBackScopeAndBinding() {
        Context root = Context.createRoot();
        try (ComponentLoader loader = new ComponentLoader(root, u -> provider(u))) {
            loader.load(List.of(Entry.of("a", "a").isolateShared("svc", "a").build(), Entry.of("b", "b").isolateShared("svc", "b").build()));
            Fiber before = loader.mounted("a").fiber;
            assertThrows(CordisException.class, () -> loader.load(List.of(Entry.of("a", "a").isolateShared("svc", "b").build(), Entry.of("b", "b").isolateShared("svc", "b").build())));
            assertSame(before, loader.mounted("a").fiber);
            assertEquals("a", root.isolate("svc", "a").get("svc"));
            assertEquals("b", root.isolate("svc", "b").get("svc"));
        }
    }

    @Test
    void concurrentReconciliationsApplyInSubmissionOrder() {
        Context root = Context.createRoot();
        var initial = new java.util.concurrent.CompletableFuture<dev.cordis.core.effect.Disposable>();
        try (ComponentLoader loader = new ComponentLoader(root, u -> Plugin.of("p", List.of(), (c, cfg) -> cfg.equals("one") ? initial : null))) {
            var first = loader.reconcileAsync(List.of(Entry.of("p", "p").config("one").build()));
            var second = loader.reconcileAsync(List.of(Entry.of("p", "p").config("two").build()));
            assertFalse(second.toCompletableFuture().isDone());
            initial.complete(dev.cordis.core.effect.Disposable.noop());
            dev.cordis.core.effect.Stages.join(first);
            dev.cordis.core.effect.Stages.join(second);
            assertEquals("two", loader.mounted("p").fiber.getEffectiveConfig());
        }
    }

}
