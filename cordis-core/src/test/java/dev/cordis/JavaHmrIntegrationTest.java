package dev.cordis;

import dev.cordis.core.runtime.*;
import dev.cordis.loader.*;
import dev.cordis.hmr.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class JavaHmrIntegrationTest {
    @TempDir
    Path dir;

    String source(String value, boolean fail) {
        return """
                import dev.cordis.core.*;
                import dev.cordis.core.runtime.*;
                public class Dynamic implements Plugin {
                    public Object apply(Context ctx,Object config) {
                        ctx.provide("dynamic", "%s");
                        %s
                    }
                }
                """.formatted(value, fail ? "throw new IllegalStateException(\"startup\");" : "return null;");
    }

    @Test
    void compilesSourceAndReloadsNewClassGeneration() throws Exception {
        Path file = dir.resolve("Dynamic.java");
        Files.writeString(file, source("v1", false));
        Context root = Context.createRoot();
        try (JavaModuleResolver resolver = new JavaModuleResolver(dir);
             ComponentLoader loader = new ComponentLoader(root, resolver)) {
            loader.load(List.of(Entry.of("d", resolver.url(file)).build()));
            assertEquals("v1", root.get("dynamic"));
            ClassLoader old = loader.mounted("d").plugin.getClass().getClassLoader();
            try (HotReloadService hmr = new HotReloadService(loader, resolver, Duration.ofDays(1))) {
                Files.writeString(file, source("v2", false));
                hmr.reloadNow();
                assertEquals("v2", root.get("dynamic"));
                assertNotSame(old, loader.mounted("d").plugin.getClass().getClassLoader());
            }
        }
        assertEquals(0, root.registry().size());
    }

    @Test
    void syntaxAndStartupFailuresRestoreOldCodeAndCache() throws Exception {
        Path file = dir.resolve("Dynamic.java");
        Files.writeString(file, source("v1", false));
        Context root = Context.createRoot();
        try (JavaModuleResolver resolver = new JavaModuleResolver(dir); ComponentLoader loader = new ComponentLoader(root, resolver)) {
            loader.load(List.of(Entry.of("d", resolver.url(file)).build()));
            Object old = loader.mounted("d").plugin;
            try (HotReloadService hmr = new HotReloadService(loader, resolver, Duration.ofDays(1))) {
                Files.writeString(file, "this is not Java");
                assertThrows(IllegalArgumentException.class, hmr::reloadNow);
                assertSame(old, loader.mounted("d").plugin);
                assertEquals("v1", root.get("dynamic"));
                Files.writeString(file, source("bad", true));
                assertThrows(IllegalStateException.class, hmr::reloadNow);
                assertSame(old, loader.mounted("d").plugin);
                assertSame(old, resolver.resolve(resolver.url(file)));
                assertEquals("v1", root.get("dynamic"));
                assertEquals(1, root.registry().size());
                Files.writeString(file, source("v3", false));
                hmr.reloadNow();
                assertEquals("v3", root.get("dynamic"));
            }
        }
    }

    @Test
    void watchServiceDetectsActualFileChanges() throws Exception {
        Path file = dir.resolve("Dynamic.java");
        Files.writeString(file, source("before", false));
        Context root = Context.createRoot();
        CountDownLatch reloaded = new CountDownLatch(1);
        root.on("hmr/reload", args -> {
            reloaded.countDown();
            return null;
        });
        try (JavaModuleResolver resolver = new JavaModuleResolver(dir); ComponentLoader loader = new ComponentLoader(root, resolver)) {
            loader.load(List.of(Entry.of("d", resolver.url(file)).build()));
            try (HotReloadService hmr = new HotReloadService(loader, resolver, Duration.ofMillis(150))) {
                Files.writeString(file, source("after", false));
                assertTrue(reloaded.await(10, TimeUnit.SECONDS), "Watcher did not reload source");
                assertEquals("after", root.get("dynamic"));
            }
        }
        root.fiber().dispose();
    }

    @Test
    void sharedHelperChangesReloadAllGenerationEntries() throws Exception {
        Path file = dir.resolve("Dynamic.java");
        Files.writeString(file, source("placeholder", false).replace("\"placeholder\"", "Helper.value()"));
        Files.writeString(dir.resolve("Helper.java"), "public class Helper { public static String value(){return \"a\";} }");
        Context root = Context.createRoot();
        try (JavaModuleResolver resolver = new JavaModuleResolver(dir); ComponentLoader loader = new ComponentLoader(root, resolver)) {
            loader.load(List.of(Entry.of("d", resolver.url(file)).build()));
            assertEquals("a", root.get("dynamic"));
            assertTrue(resolver.imports(resolver.url(file)).contains(resolver.url(dir.resolve("Helper.java"))));
            try (HotReloadService hmr = new HotReloadService(loader, resolver, Duration.ofDays(1))) {
                Files.writeString(dir.resolve("Helper.java"), "public class Helper { public static String value(){return \"b\";} }");
                hmr.reloadNow();
                assertEquals("b", root.get("dynamic"));
            }
        }
    }

    @Test
    void applicationOwnsServicesLoadersAndCompilerResources() throws Exception {
        Files.writeString(dir.resolve("Dynamic.java"), source("application", false));
        Path config = dir.resolve("cordis.yaml");
        ConfigFile.write(config, List.of(Entry.of("d", "Dynamic.java").build()));
        Context root;
        try (CordisApplication application = CordisApplication.open(config, dir, false)) {
            root = application.context;
            assertEquals("application", root.get("dynamic"));
            assertNotNull(root.get("timer"));
        }
        assertEquals(FiberState.DISPOSED, root.fiber().state);
        assertEquals(0, root.registry().size());
        assertNull(root.get("dynamic"));
    }

}
