package dev.cordis;

import dev.cordis.core.*;
import dev.cordis.core.runtime.*;
import dev.cordis.loader.*;
import dev.cordis.hmr.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HmrRegressionTest {
    @Test
    void classificationDiscoversDeepDependenciesWithoutMutatingIterator() {
        ModuleGraph graph = u -> switch (u) {
            case "S" -> new LinkedHashSet<>(List.of("x", "z"));
            case "x" -> Set.of("y");
            case "y" -> Set.of("q");
            default -> Set.of();
        };
        var classification = HmrEngine.classify(Set.of("S"), Set.of(), graph);
        assertEquals(Set.of("S"), classification.accepted());
        assertEquals(Set.of("x", "y", "q", "z"), classification.declined());
    }

    @Test
    void externalChangedModuleIsNeverAccepted() {
        var result = HmrEngine.classify(Set.of("core"), Set.of("core"), u -> Set.of());
        assertFalse(result.accepted().contains("core"));
    }

    @Test
    void startupFailureRollbackLeavesNoFailedRuntime() throws Exception {
        Context root = Context.createRoot();
        Plugin old = Plugin.of("old", List.of(), (c, cfg) -> {
            c.provide("v", "old");
            return null;
        });
        try (ComponentLoader loader = new ComponentLoader(root, u -> old)) {
            loader.load(List.of(Entry.of("p", "p").build()));
            Plugin broken = Plugin.of("broken", List.of(), (c, cfg) -> {
                throw new IllegalStateException("broken");
            });
            assertThrows(IllegalStateException.class, () -> HmrEngine.reload(loader, List.of("p"), Map.of("p", broken), null));
            assertEquals("old", root.get("v"));
            assertEquals(1, root.registry().size());
            assertNull(root.registry().get(broken));
            assertFalse(loader.mounted("p").entry.disabled);
        }
    }

    @Test
    void failedImportDoesNotDisposeWorkingInstance() {
        Context root = Context.createRoot();
        Plugin old = Plugin.of("old", List.of(), (c, cfg) -> null);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (ComponentLoader loader = new ComponentLoader(root, u -> {
            if (count.getAndIncrement() > 0) throw new IllegalArgumentException("syntax");
            return old;
        })) {
            loader.load(List.of(Entry.of("p", "p").build()));
            Fiber before = loader.mounted("p").fiber;
            assertThrows(IllegalArgumentException.class, () -> HmrEngine.reload(loader, List.of("p"), Map.of(), null));
            assertSame(before, loader.mounted("p").fiber);
            assertEquals(FiberState.ACTIVE, before.state);
        }
    }

    @Test
    void rollbackCleansEveryCandidateBeforeRestoringProviders() throws Exception {
        Context root = Context.createRoot();
        Plugin a = Plugin.of("a", List.of(), (c, cfg) -> {
            c.provide("a", 1);
            return null;
        });
        Plugin b = Plugin.of("b", List.of("a"), (c, cfg) -> {
            c.provide("b", 1);
            return null;
        });
        try (ComponentLoader loader = new ComponentLoader(root, u -> u.equals("a") ? a : b)) {
            loader.load(List.of(Entry.of("a", "a").build(), Entry.of("b", "b").build()));
            Plugin freshA = Plugin.of("a2", List.of(), (c, cfg) -> {
                c.provide("a", 2);
                return null;
            });
            Plugin badB = Plugin.of("b2", List.of("a"), (c, cfg) -> {
                c.provide("b", 2);
                throw new IllegalStateException("fail");
            });
            assertThrows(IllegalStateException.class, () -> HmrEngine.reload(loader, List.of("a", "b"), Map.of("a", freshA, "b", badB), null));
            assertEquals(1, (int) root.<Integer>get("a"));
            assertEquals(1, (int) root.<Integer>get("b"));
            assertEquals(2, root.registry().size());
        }
    }

    @Test
    void staleDetectionDoesNotDependOnEntryIterationOrder() {
        ModuleGraph graph = u -> switch (u) {
            case "earlier" -> Set.of("shared");
            case "later" -> Set.of("changed", "shared");
            default -> Set.of();
        };
        assertEquals(Set.of("earlier", "later"), HmrEngine.detectStale(List.of("earlier", "later"), Set.of("changed"), Set.of(), graph));
    }

    @Test void startupErrorAlsoRollsBackInsteadOfLosingOldPlugin() throws Exception {
        Context root=Context.createRoot();Plugin old=Plugin.of("old",List.of(),(ctx,cfg)->{ctx.provide("v","old");return null;});
        try(ComponentLoader loader=new ComponentLoader(root,u->old)) {
            loader.load(List.of(Entry.of("p","p").build()));
            Plugin broken=Plugin.of("bad",List.of(),(ctx,cfg)->{throw new AssertionError("startup error");});
            assertThrows(AssertionError.class,()->HmrEngine.reload(loader,List.of("p"),Map.of("p",broken),null));
            assertEquals("old",root.get("v"));assertEquals(1,root.registry().size());
        }
    }

}
