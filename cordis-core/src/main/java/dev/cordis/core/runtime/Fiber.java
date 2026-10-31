package dev.cordis.core.runtime;

import dev.cordis.core.*;
import dev.cordis.core.coeffect.*;
import dev.cordis.core.effect.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * One component instance. All lifecycle mutations are serialized by the root context.
 */
public final class Fiber {
    static final String INACTIVE = "__INACTIVE__";
    public volatile Long uid;
    public final Context parentCtx;
    public final Context ctx;
    public final Map<String, Object> inject;
    public final PluginRuntime runtime;
    public final Plugin plugin;
    public volatile Map<String, Impl> store;
    public volatile Object config;
    public volatile Object rawConfig;
    public volatile FiberState state = FiberState.PENDING;
    public volatile String target = INACTIVE;
    private String epoch = INACTIVE;
    private long revision;
    private boolean running;
    private boolean loaded;
    private Throwable error;
    private CompletableFuture<Void> stable = Stages.done();
    private CompletionStage<Void> retirement;
    private CompletableFuture<Void> updateBarrier = Stages.done();
    private Disposable retireHandle;
    private final Map<String, Impl> pendingStore = new LinkedHashMap<>();
    private final List<EffectRecord> records = new ArrayList<>();
    private final Deque<EffectMeta> metaStack = new ArrayDeque<>();
    private final Set<Impl> provisions = new LinkedHashSet<>();
    private final Set<Impl> retained = new LinkedHashSet<>();
    private final Map<String, Object> attachments = new HashMap<>();

    public Object attachment(String key) {
        return root().atomic(() -> attachments.get(key));
    }

    public void attach(String key, Object value) {
        root().atomic(() -> {
            attachments.put(key, value);
            return null;
        });
    }

    /**
     * Commit an update handled in place by a component's internal/update hook.
     */
    public void commitConfig(Object value) {
        root().atomic(() -> {
            assertActive();
            rawConfig = config = value;
            ctx.events().emit(ctx, "internal/config-updated", this, value);
            return null;
        });
    }

    Fiber(Context parent, Object raw, Plugin plugin, PluginRuntime runtime) {
        this.parentCtx = parent;
        this.rawConfig = raw;
        this.plugin = plugin;
        this.runtime = runtime;
        inject = runtime == null ? Map.of() : new LinkedHashMap<>(plugin.injections());
        if (runtime == null) {
            uid = 0L;
            ctx = parent;
            state = FiberState.ACTIVE;
            store = new LinkedHashMap<>();
            epoch = target = "";
            loaded = true;
            return;
        }
        uid = (long) parent.registry().nextUid();
        ctx = parent.deriveChild(this);
        inject.forEach((name, value) -> {
            if (value != null) ctx.interceptSelf(name, value);
        });
        retireHandle = parent.fiber().effect(c -> {
            runtime.fibers.add(this);
            return (AsyncDisposable) this::retire;
        }, "ctx.plugin()");
        ctx.events().emit(null, "internal/plugin", this);
        if (uid != null && parent.fiber().state != FiberState.UNLOADING) {
            inject.keySet().forEach(this::checkImpl);
            refresh();
        }
    }

    private Context root() {
        return ctx.getRoot();
    }

    public String name() {
        Fiber f = this;
        while (f.runtime != null) {
            if (f.runtime.name != null && !f.runtime.name.isEmpty()) return f.runtime.name;
            f = f.parentCtx.fiber();
        }
        return "root";
    }

    public void assertActive() {
        if (uid == null) throw new CordisException(CordisException.Code.INACTIVE_EFFECT);
    }

    /**
     * An owner-visible setup barrier is installed before any user code runs.
     */
    private final class EffectRecord implements AsyncDisposable {
        Fiber owner = Fiber.this;
        final EffectMeta meta;
        final List<Disposable> inverses = new ArrayList<>();
        final CompletableFuture<Void> setup = new CompletableFuture<>();
        boolean armed = true;
        CompletableFuture<Void> cleanup;

        EffectRecord(String label) {
            meta = new EffectMeta(label);
        }

        @Override
        public CompletionStage<Void> disposeAsync() {
            return root().atomic(() -> {
                if (cleanup != null) return cleanup;
                armed = false;
                cleanup = new CompletableFuture<>();
                setup.handle((v, e) -> null).thenCompose(v -> root().atomic(() -> unwind(inverses)))
                        .whenComplete((v, e) -> root().atomic(() -> {
                            owner.records.remove(this);
                            if (e == null) cleanup.complete(null);
                            else cleanup.completeExceptionally(Stages.cause(e));
                            return null;
                        }));
                return cleanup;
            });
        }

        @Override
        public void dispose() {
            boolean nested = root().inRuntime();
            CompletionStage<Void> task = disposeAsync();
            if (!nested) Stages.join(task);
        }
    }

    public Disposable effect(EffectCallback callback, String label) {
        return root().atomic(() -> {
            assertActive();
            if (state == FiberState.UNLOADING) throw new CordisException(CordisException.Code.INACTIVE_EFFECT);
            EffectRecord record = new EffectRecord(label);
            records.add(record);
            if (!metaStack.isEmpty()) metaStack.peek().children.add(record.meta);
            metaStack.push(record.meta);
            try {
                CompletionStage<Void> task = collect(callback.execute(ctx), record.inverses, () -> record.armed);
                task.whenComplete((v, e) -> root().atomic(() -> {
                    if (e == null) record.setup.complete(null);
                    else {
                        record.setup.completeExceptionally(Stages.cause(e));
                        record.disposeAsync();
                    }
                    return null;
                }));
                if (record.setup.isCompletedExceptionally()) Stages.join(record.setup);
            } catch (Throwable e) {
                record.setup.completeExceptionally(e);
                record.disposeAsync();
                throw Stages.propagate(e);
            } finally {
                metaStack.pop();
            }
            return record;
        });
    }

    private CompletionStage<Void> collect(Object value, List<Disposable> sink, BooleanSupplier guard) {
        if (value == null) return Stages.done();
        if (value instanceof Disposable disposable) {
            sink.add(disposable);
            return Stages.done();
        }
        if (value instanceof CompletionStage<?> stage)
            return stage.thenCompose(result -> root().atomic(() -> collect(result, sink, guard)));
        if (value instanceof AsyncEffect iterator) return collectAsync(iterator, sink, guard);
        if (value instanceof Iterable<?> iterable) {
            Iterator<?> iterator = iterable.iterator();
            // Test the guard BEFORE advancing the lazy iterator. Always retain a completed step's inverse.
            while (guard.getAsBoolean() && iterator.hasNext()) {
                Object next = iterator.next();
                if (next == null) continue;
                if (!(next instanceof Disposable disposable))
                    throw new IllegalArgumentException("Invalid effect stage: " + next);
                sink.add(disposable);
            }
            return Stages.done();
        }
        throw new IllegalArgumentException("Invalid effect: " + value.getClass());
    }

    private CompletionStage<Void> collectAsync(AsyncEffect iterator, List<Disposable> sink, BooleanSupplier guard) {
        // Trampoline completed steps so a long synchronous producer cannot overflow the stack.
        while (guard.getAsBoolean()) {
            CompletionStage<AsyncEffect.Step> next = iterator.next();
            CompletableFuture<AsyncEffect.Step> future = next.toCompletableFuture();
            if (!future.isDone()) return next.thenCompose(step -> root().atomic(() -> {
                if (step != null && step.inverse() != null) sink.add(step.inverse());
                return step == null || step.done() ? Stages.done() : collectAsync(iterator, sink, guard);
            }));
            AsyncEffect.Step step = future.join();
            if (step != null && step.inverse() != null) sink.add(step.inverse());
            if (step == null || step.done()) return Stages.done();
        }
        return Stages.done();
    }

    private CompletionStage<Void> unwind(List<? extends Disposable> inverses) {
        List<Disposable> copy = new ArrayList<>(inverses);
        inverses.clear();
        CompletionStage<Void> task = Stages.done();
        List<Throwable> errors = new ArrayList<>();
        for (int i = copy.size() - 1; i >= 0; i--) {
            Disposable disposable = copy.get(i);
            task = task.thenCompose(v -> root().atomic(() -> Stages.dispose(disposable)))
                    .handle((v, e) -> {
                        if (e != null) errors.add(Stages.cause(e));
                        return null;
                    });
        }
        return task.thenCompose(v -> {
            if (errors.isEmpty()) return Stages.done();
            Throwable first = errors.getFirst();
            errors.stream().skip(1).filter(e -> e != first).forEach(first::addSuppressed);
            return CompletableFuture.failedFuture(first);
        });
    }

    public List<EffectMeta> getEffects() {
        return root().atomic(() -> records.stream().map(record -> record.meta).toList());
    }

    public void checkImpl(String name) {
        root().atomic(() -> {
            Impl impl = ctx.reflect().getImpl(ctx, name, true);
            try {
                Object candidate = impl == null ? null : impl.value instanceof ContextualService<?> service ? service.inContext(ctx) : impl.value;
                if (impl == null || (impl.check != null && !impl.check.test(candidate))) pendingStore.remove(name);
                else pendingStore.put(name, impl);
            } catch (Throwable e) {
                ctx.logger("cordis").error(e);
                pendingStore.remove(name);
            }
            return null;
        });
    }

    public void refresh() {
        root().atomic(() -> {
            StringBuilder digest = new StringBuilder().append(revision);
            for (String name : inject.keySet()) {
                Impl impl = pendingStore.get(name);
                if (impl == null) {
                    digest = null;
                    break;
                }
                digest.append(':').append(impl.uid);
            }
            target = uid == null || digest == null ? INACTIVE : digest.toString();
            if (error == null && !Objects.equals(epoch, target)) {
                epoch = target;
                drive();
            }
            return null;
        });
    }

    private void drive() {
        if (running) return;
        if (!loaded && (INACTIVE.equals(epoch) || uid == null) && records.isEmpty()) {
            setState(resting());
            return;
        }
        running = true;
        stable = new CompletableFuture<>();
        if (loaded || !records.isEmpty()) unload();
        else load();
    }

    private void load() {
        if (uid == null || INACTIVE.equals(epoch)) {
            finish();
            return;
        }
        loaded = true;
        if (runtime == null) {
            store = new LinkedHashMap<>();
            finish();
            return;
        }
        String startEpoch = epoch;
        store = new LinkedHashMap<>(pendingStore);
        for (Impl impl : store.values())
            if (impl.fiber != this) {
                impl.retain(this);
                retained.add(impl);
            }
        setState(FiberState.LOADING);
        // The apply return value is tracked by the same owner-visible runner as ctx.effect.
        EffectRecord body = new EffectRecord(name() + ".apply");
        records.add(body);
        metaStack.push(body.meta);
        CompletionStage<Void> execution;
        try {
            config = resolveConfig(rawConfig);
            execution = collect(plugin.apply(ctx, config), body.inverses, () -> uid != null && Objects.equals(epoch, startEpoch));
        } catch (Throwable e) {
            execution = CompletableFuture.failedFuture(e);
        } finally {
            metaStack.pop();
        }
        execution.whenComplete((v, failure) -> root().atomic(() -> {
            if (records.remove(body)) records.add(body);
            if (failure == null) body.setup.complete(null);
            else body.setup.completeExceptionally(Stages.cause(failure));
            // Explicit effects may still be setting up after apply returns.
            CompletableFuture<?>[] setups = records.stream().map(record -> record.setup).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(setups).whenComplete((ignored, setupError) -> root().atomic(() -> {
                if (setupError != null && Objects.equals(epoch, startEpoch) && uid != null) {
                    error = Stages.cause(setupError);
                    epoch = INACTIVE;
                    ctx.logger(name()).error(error);
                }
                if (uid != null && Objects.equals(epoch, startEpoch)) {
                    finish();
                } else unload();
                return null;
            }));
            return null;
        }));
    }

    private void unload() {
        setState(FiberState.UNLOADING); // publish departure before waiting or invoking any inverse
        CompletableFuture<?>[] dependents = provisions.stream().map(Impl::drained)
                .map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(dependents).thenCompose(v -> root().atomic(() -> unwind(records)))
                .whenComplete((v, failure) -> root().atomic(() -> {
                    if (failure != null) ctx.logger(name()).error(Stages.cause(failure));
                    retained.forEach(impl -> impl.release(this));
                    retained.clear();
                    store = null;
                    loaded = false;
                    attachments.clear();
                    if (uid != null && !INACTIVE.equals(epoch) && error == null) load();
                    else finish();
                    return null;
                }));
    }

    private FiberState resting() {
        if (uid == null) return FiberState.DISPOSED;
        if (error != null) return FiberState.FAILED;
        return loaded ? FiberState.ACTIVE : FiberState.PENDING;
    }

    private void finish() {
        CompletableFuture<Void> completed = stable;
        running = false;
        setState(resting());
        completed.complete(null);
    }

    private void setState(FiberState next) {
        FiberState old = state;
        state = next;
        if (old == next) return;
        ctx.events().emit(null, "internal/status", this, old);
        if ((old == FiberState.ACTIVE) != (next == FiberState.ACTIVE)) {
            for (Impl impl : List.copyOf(provisions)) ctx.reflect().notify(impl.context, List.of(impl.name));
        }
    }

    Object resolveConfig(Object raw) {
        Object value = ctx.events().waterfall(ctx, "internal/config", raw, (Supplier<Object>) () -> raw);
        if (plugin != null && plugin.schema() != null) return plugin.schema().validate(value);
        return value;
    }

    public Object getRawConfig() {
        return rawConfig;
    }

    public Object getEffectiveConfig() {
        return config;
    }

    public CompletionStage<Fiber> awaitAsync() {
        return root().atomic(() -> {
            CompletableFuture<Void> observedUpdate = updateBarrier;
            return CompletableFuture.allOf(stable, observedUpdate).thenCompose(v -> root().atomic(() -> {
                if (running || observedUpdate != updateBarrier) return awaitAsync();
                return error == null ? CompletableFuture.completedFuture(this) : CompletableFuture.failedFuture(error);
            }));
        });
    }

    public Fiber await() {
        if (root().inRuntime() && running)
            throw new IllegalStateException("Use awaitAsync inside a lifecycle callback");
        Stages.join(awaitAsync());
        return this;
    }

    public Fiber restart() {
        root().atomic(() -> {
            assertActive();
            error = null;
            revision++;
            refresh();
            return null;
        });
        if (!root().inRuntime()) await();
        return this;
    }

    public void update(Object newConfig) {
        root().atomic(() -> {
            assertActive();
            rawConfig = newConfig;
            Object candidate = state == FiberState.ACTIVE ? resolveConfig(newConfig) : newConfig;
            Object updateResult = ctx.events().waterfall(ctx, "internal/update", candidate, Boolean.FALSE, (Supplier<Object>) () -> {
                rawConfig = newConfig;
                config = candidate;
                error = null;
                revision++;
                refresh();
                ctx.events().emit(ctx, "internal/config-updated", this, newConfig);
                return null;
            });
            updateBarrier = updateResult instanceof CompletionStage<?> task ? task.thenApply(v -> (Void) null).toCompletableFuture() : Stages.done();
            return null;
        });
        if (!root().inRuntime()) await();
    }

    public CompletionStage<Fiber> updateAsync(Object value) {
        try {
            return root().atomic(() -> {
                update(value);
                return awaitAsync();
            });
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletionStage<Fiber> restartAsync() {
        try {
            return root().atomic(() -> {
                restart();
                return awaitAsync();
            });
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletionStage<Void> retire() {
        return root().atomic(() -> {
            if (retirement != null) return retirement;
            CompletableFuture<Void> result = new CompletableFuture<>();
            retirement = result;
            uid = null;
            target = epoch = INACTIVE;
            ctx.events().emit(null, "internal/plugin", this);
            drive();
            stable.whenComplete((v, e) -> root().atomic(() -> {
                if (runtime != null) {
                    runtime.fibers.remove(this);
                    if (runtime.fibers.isEmpty()) ctx.registry().removeRuntime(runtime);
                }
                result.complete(null);
                return null;
            }));
            return result;
        });
    }

    /**
     * Transfer the structural inverse without disposing it when a managed entry moves.
     */
    public void transferOwnership(Fiber nextOwner) {
        root().atomic(() -> {
            assertActive();
            nextOwner.assertActive();
            if (nextOwner.root() != root()) throw new IllegalArgumentException("Different application roots");
            if (!(retireHandle instanceof Fiber.EffectRecord record))
                throw new IllegalStateException("Root has no structural owner");
            if (record.owner != nextOwner) {
                record.owner.records.remove(record);
                nextOwner.records.add(record);
                record.owner = nextOwner;
            }
            return null;
        });
    }

    public CompletionStage<Void> disposeAsync() {
        if (retireHandle instanceof AsyncDisposable async) return async.disposeAsync();
        return retire();
    }

    public void dispose() {
        boolean nested = root().inRuntime();
        CompletionStage<Void> task = disposeAsync();
        if (!nested) Stages.join(task);
    }

    public Set<Impl> provisions() {
        return root().atomic(() -> Set.copyOf(provisions));
    }

    Disposable provide(Context from, String name, Object value, Predicate<Object> check) {
        return effect(c -> {
            ReflectService reflect = ctx.reflect();
            RealmKey key = from.resolveRealm(name);
            if (reflect.bindingAt(key) != null || reflect.accessor(name) != null)
                throw new CordisException(CordisException.Code.DUPLICATE_PROVIDE, "duplicate service: " + name);
            Impl impl = new Impl(name, this, from, key, value, check);
            provisions.add(impl);
            reflect.bind(key, impl);
            if (store != null) store.put(name, impl);
            if (state == FiberState.ACTIVE) reflect.notify(from, List.of(name));
            return (AsyncDisposable) () -> root().atomic(() -> {
                impl.withdrawn = true;
                reflect.notify(from, List.of(name));
                return impl.drained().thenRun(() -> root().atomic(() -> {
                    if (reflect.bindingAt(impl.realm) == impl) reflect.unbind(impl.realm);
                    provisions.remove(impl);
                    if (store != null && store.get(name) == impl) store.remove(name);
                    reflect.notify(from, List.of(name));
                    return null;
                }));
            });
        }, "ctx.provide(\"" + name + "\")");
    }

    @Override
    public String toString() {
        return "Fiber<" + name() + "#" + uid + ":" + state + ">";
    }
}
