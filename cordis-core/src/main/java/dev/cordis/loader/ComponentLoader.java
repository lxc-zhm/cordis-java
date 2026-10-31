package dev.cordis.loader;

import dev.cordis.core.*;
import dev.cordis.core.runtime.*;
import dev.cordis.core.coeffect.*;
import dev.cordis.core.effect.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A keyed configuration tree. Metadata changes and lifecycle changes share the root serializer.
 */
public final class ComponentLoader implements AutoCloseable {
    public static final class Mounted {
        public final Entry entry;
        public final Fiber fiber;
        public final Plugin plugin;
        public final Context ctx;

        Mounted(Entry entry, Fiber fiber, Plugin plugin, Context ctx) {
            this.entry = entry;
            this.fiber = fiber;
            this.plugin = plugin;
            this.ctx = ctx;
        }

        Mounted with(Entry value) {
            return new Mounted(value, fiber, plugin, ctx);
        }
    }

    private final Context parent;
    private final PluginResolver resolver;
    private final Map<String, Mounted> mounted = new LinkedHashMap<>();
    private final Map<String, Map<String, RealmKey>> privateRealms = new HashMap<>();
    private Consumer<List<Entry>> onChange = entries -> {
    };
    private final Disposable ownership;
    private final Disposable configHook;
    private final Disposable pluginHook;
    private CompletableFuture<Void> operationTail = Stages.done();
    private boolean closed;
    private boolean closing;
    private boolean reconciling;

    public ComponentLoader(Context parent, PluginResolver resolver) {
        this.parent = parent;
        this.resolver = resolver;
        configHook = parent.onGlobal("internal/config-updated", args -> {
            Fiber fiber = (Fiber) args[0];
            for (var m : List.copyOf(mounted.values()))
                if (m.fiber == fiber) {
                    mounted.put(m.entry.id, m.with(m.entry.withConfig(args[1])));
                    changed();
                    break;
                }
            return null;
        });
        pluginHook = parent.onGlobal("internal/plugin", args -> {
            Fiber fiber = (Fiber) args[0];
            if (fiber.uid == null && !closing && !reconciling) {
                for (var m : List.copyOf(mounted.values()))
                    if (m.fiber == fiber) {
                        mounted.put(m.entry.id, new Mounted(m.entry.withDisabled(true), null, m.plugin, m.ctx));
                        changed();
                        break;
                    }
            }
            return null;
        });
        ownership = parent.effect(c -> (AsyncDisposable) () -> {
            closed = true;
            return disposeAllAsync();
        }, "loader ownership");
    }

    /**
     * All currently instantiated configuration subtrees, in parent-first order.
     */
    public List<ComponentLoader> tree() {
        return parent.atomic(() -> {
            List<ComponentLoader> result = new ArrayList<>();
            result.add(this);
            for (Mounted m : mounted.values())
                if (m.fiber != null && m.fiber.attachment("loader") instanceof ComponentLoader nested)
                    result.addAll(nested.tree());
            return List.copyOf(result);
        });
    }

    public Context context() {
        return parent;
    }

    public Plugin resolve(String url) throws Exception {
        return resolver.resolve(url);
    }

    public Mounted mounted(String id) {
        return parent.atomic(() -> mounted.get(id));
    }

    public List<String> ids() {
        return parent.atomic(() -> List.copyOf(mounted.keySet()));
    }

    public List<Entry> snapshot() {
        return parent.atomic(() -> mounted.values().stream().map(m -> m.entry).toList());
    }

    public void onChange(Consumer<List<Entry>> listener) {
        parent.atomic(() -> {
            onChange = Objects.requireNonNull(listener);
            return null;
        });
    }

    private void changed() {
        if (!closing && !reconciling) onChange.accept(snapshot());
    }

    /**
     * One ordered orchestration queue per configuration subtree. Never hold the runtime lock while waiting.
     */
    private <T> CompletionStage<T> enqueue(java.util.function.Supplier<CompletionStage<T>> action) {
        return parent.atomic(() -> {
            CompletableFuture<T> result = new CompletableFuture<>();
            CompletableFuture<Void> previous = operationTail;
            CompletableFuture<Void> released = new CompletableFuture<>();
            operationTail = released;
            previous.handle((v, e) -> null).thenCompose(v -> parent.atomic(action)).whenComplete((value, error) -> parent.atomic(() -> {
                released.complete(null);
                if (error == null) result.complete(value);
                else result.completeExceptionally(Stages.cause(error));
                return null;
            }));
            return result;
        });
    }

    public <T> T transaction(Callable<T> action) throws Exception {
        if (parent.inRuntime())
            throw new IllegalStateException("A blocking loader transaction cannot run inside a lifecycle callback");
        synchronized (parent.getRoot()) {
            return runTransaction(action);
        }
    }

    private <T> T runTransaction(Callable<T> action) throws Exception {
        CompletionStage<T> task = enqueue(() -> {
            CompletableFuture<T> result = new CompletableFuture<>();
            Thread.ofVirtual().name("cordis-loader-transaction").start(() -> {
                try {
                    result.complete(action.call());
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            return result;
        });
        try {
            return task.toCompletableFuture().get();
        } catch (ExecutionException error) {
            Throwable cause = Stages.cause(error);
            if (cause instanceof Exception exception) throw exception;
            throw (Error) cause;
        }
    }

    public final class Lease implements AutoCloseable {
        private final CompletableFuture<Void> release;

        private Lease(CompletableFuture<Void> release) {
            this.release = release;
        }

        @Override
        public void close() {
            release.complete(null);
        }
    }

    /**
     * Acquire a subtree's orchestration queue for a multi-subtree transaction.
     */
    public CompletionStage<Lease> acquireOperations() {
        CompletableFuture<Lease> acquired = new CompletableFuture<>();
        enqueue(() -> {
            CompletableFuture<Void> release = new CompletableFuture<>();
            acquired.complete(new Lease(release));
            return release;
        });
        return acquired;
    }

    public void load(List<Entry> desired) {
        reconcile(desired);
    }

    public void reconcile(List<Entry> desired) {
        boolean nested = parent.inRuntime();
        if (nested) {
            reconcileAsync(desired);
            return;
        }
        synchronized (parent.getRoot()) {
            Stages.join(reconcileAsync(desired));
        }
    }

    public CompletionStage<Void> reconcileAsync(List<Entry> desired) {
        Set<String> unique = new HashSet<>();
        for (Entry entry : desired)
            if (entry.id == null || entry.id.isBlank() || entry.url == null || !unique.add(entry.id))
                throw new IllegalArgumentException("Missing or duplicate entry id: " + entry.id);
        return enqueue(() -> {
            if (closed) throw new IllegalStateException("Loader is closed");
            reconciling = true;
            List<CompletableFuture<?>> removals = new ArrayList<>();
            for (String id : new ArrayList<>(mounted.keySet()))
                if (!unique.contains(id)) removals.add(removeAsync(id).toCompletableFuture());
            CompletionStage<Void> task = CompletableFuture.allOf(removals.toArray(CompletableFuture[]::new)).thenCompose(v -> parent.atomic(() -> {
                List<CompletableFuture<?>> updates = new ArrayList<>();
                // Start all modules before awaiting any async apply; startup must not impose entry order.
                for (Entry entry : desired) updates.add(applyAsync(entry).toCompletableFuture());
                return CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new));
            }));
            return task.whenComplete((v, e) -> parent.atomic(() -> {
                reconciling = false;
                return null;
            }));
        });
    }

    private CompletionStage<Void> removeAsync(String id) {
        Mounted old = mounted.remove(id);
        privateRealms.remove(id);
        return old == null || old.fiber == null ? Stages.done() : old.fiber.disposeAsync();
    }

    private CompletionStage<Void> applyAsync(Entry entry) {
        Mounted old = mounted.get(entry.id);
        if (old == null || !Objects.equals(old.entry.url, entry.url) || old.entry.disabled != entry.disabled) {
            return removeAsync(entry.id).thenRun(() -> parent.atomic(() -> {
                        mount(entry, null);
                        return null;
                    }))
                    .thenCompose(v -> settleEntry(entry.id));
        }
        if (entry.disabled) {
            mounted.put(entry.id, old.with(entry));
            return Stages.done();
        }
        if (old.fiber == null) {
            mount(entry, null);
            return settleEntry(entry.id);
        }
        if (!Objects.equals(old.entry.isolate, entry.isolate)) old.ctx.patchRealms(realms(entry));
        if (!Objects.equals(old.entry.intercept, entry.intercept)) old.ctx.patchIntercept(entry.intercept);
        mounted.put(entry.id, old.with(entry));
        if (!Objects.equals(old.entry.config, entry.config)) {
            try {
                old.fiber.update(entry.config);
            } catch (RuntimeException error) {
                parent.logger("loader").error(error);
            }
        }
        return settleEntry(entry.id);
    }

    private CompletionStage<Void> settleEntry(String id) {
        Mounted m = mounted(id);
        return m == null || m.fiber == null ? Stages.done() : m.fiber.awaitAsync().handle((f, e) -> null);
    }

    private Map<String, RealmKey> realms(Entry entry) {
        Map<String, RealmKey> result = new LinkedHashMap<>();
        entry.isolate.forEach((name, label) -> result.put(name, label instanceof RealmKey key ? key :
                Boolean.TRUE.equals(label) ? privateRealms.computeIfAbsent(entry.id, k -> new HashMap<>())
                        .computeIfAbsent(name, k -> RealmKey.fresh(name + "#" + entry.id)) : RealmKey.shared(name, label)));
        return result;
    }

    private Context derive(Entry entry) {
        Context context = parent.extend();
        context.patchRealms(realms(entry));
        context.patchIntercept(entry.intercept);
        return context;
    }

    private void mount(Entry entry, Plugin forced) {
        if (closed || parent.fiber().uid == null) return;
        if (entry.disabled) {
            mounted.put(entry.id, new Mounted(entry, null, null, null));
            return;
        }
        Context context = derive(entry);
        try {
            Plugin plugin = forced != null ? forced : resolver.resolve(entry.url);
            Fiber fiber = context.plugin(plugin, entry.config);
            mounted.put(entry.id, new Mounted(entry, fiber, plugin, context));
            fiber.awaitAsync().whenComplete((f, error) -> {
                if (error != null) parent.logger("loader").error(Stages.cause(error));
            });
        } catch (Exception error) {
            parent.logger("loader").error(error);
            mounted.put(entry.id, new Mounted(entry, null, null, context));
        }
    }

    private void unmount(String id) {
        Mounted old = mounted.remove(id);
        if (old != null && old.fiber != null) old.fiber.dispose();
        privateRealms.remove(id);
    }

    /**
     * Move a live entry between groups; its fiber, private realms and resources retain identity.
     */
    public void move(String id, ComponentLoader destination) throws Exception {
        if (destination == this) return;
        if (parent.getRoot() != destination.parent.getRoot())
            throw new IllegalArgumentException("Different application roots");
        transaction(() -> {
            try (Lease lease = destination.acquireOperations().toCompletableFuture().get()) {
                parent.atomic(() -> {
                    if (closed || destination.closed) throw new IllegalStateException("Loader is closed");
                    Mounted current = mounted.get(id);
                    if (current == null || destination.mounted.containsKey(id))
                        throw new IllegalArgumentException("Missing or conflicting entry: " + id);
                    Fiber oldOwner = parent.fiber();
                    if (current.fiber != null) current.fiber.transferOwnership(destination.parent.fiber());
                    try {
                        if (current.ctx != null) current.ctx.reparent(destination.parent);
                    } catch (RuntimeException error) {
                        if (current.fiber != null) current.fiber.transferOwnership(oldOwner);
                        throw error;
                    }
                    mounted.remove(id);
                    destination.mounted.put(id, current);
                    Map<String, RealmKey> realms = privateRealms.remove(id);
                    if (realms != null) destination.privateRealms.put(id, realms);
                    changed();
                    destination.changed();
                    return null;
                });
            }
            return null;
        });
    }

    public void detachForReload(String id) {
        Mounted old = parent.atomic(() -> {
            Mounted value = mounted.get(id);
            if (value != null) mounted.put(id, new Mounted(value.entry, null, value.plugin, value.ctx));
            return value;
        });
        if (old != null && old.fiber != null) old.fiber.dispose();
    }

    /**
     * The candidate is published before await, so failure cleanup can always find it.
     */
    public Fiber remount(String id, Plugin forced) throws Exception {
        Mounted old = mounted(id);
        if (old == null || old.entry.disabled) throw new IllegalArgumentException("Entry is not enabled: " + id);
        Plugin plugin = forced != null ? forced : resolver.resolve(old.entry.url); // preflight before retiring old code
        detachForReload(id);
        Fiber candidate = parent.atomic(() -> {
            Context context = old.ctx != null ? old.ctx : derive(old.entry);
            Fiber fiber = context.plugin(plugin, old.entry.config);
            mounted.put(id, new Mounted(old.entry, fiber, plugin, context));
            return fiber;
        });
        try {
            candidate.await();
            return candidate;
        } catch (Throwable error) {
            detachForReload(id);
            throw Stages.propagate(error);
        }
    }

    public CompletionStage<Void> awaitAllAsync() {
        return parent.atomic(() -> CompletableFuture.allOf(mounted.values().stream().filter(m -> m.fiber != null)
                .map(m -> m.fiber.awaitAsync().toCompletableFuture()).toArray(CompletableFuture[]::new)));
    }

    public CompletionStage<Void> disposeAllAsync() {
        return parent.atomic(() -> {
            closing = true;
            List<Mounted> previous = List.copyOf(mounted.values());
            mounted.clear();
            privateRealms.clear();
            CompletableFuture<?>[] tasks = previous.stream().filter(m -> m.fiber != null)
                    .map(m -> m.fiber.disposeAsync().toCompletableFuture()).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(tasks).whenComplete((v, e) -> {
                closing = false;
            });
        });
    }

    public void disposeAll() {
        boolean nested = parent.inRuntime();
        var task = disposeAllAsync();
        if (!nested) Stages.join(task);
    }

    @Override
    public void close() {
        closed = true;
        disposeAll();
        ownership.dispose();
        configHook.dispose();
        pluginHook.dispose();
    }
}
