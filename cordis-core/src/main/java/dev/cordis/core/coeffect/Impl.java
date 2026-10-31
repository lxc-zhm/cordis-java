package dev.cordis.core.coeffect;

import dev.cordis.core.runtime.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A provision has its own identity, scope and retained dependent views.
 */
public final class Impl {
    private static final AtomicLong IDS = new AtomicLong();
    public final long uid = IDS.incrementAndGet();
    public final String name;
    public final Fiber fiber;
    public final Context context;
    public RealmKey realm;
    public volatile Object value;
    public final Predicate<Object> check;
    public boolean withdrawn;
    private final Set<Fiber> dependents = new LinkedHashSet<>();
    private CompletableFuture<Void> drain;

    public Impl(String name, Fiber fiber, Context context, RealmKey realm, Object value, Predicate<Object> check) {
        this.name = name;
        this.fiber = fiber;
        this.context = context;
        this.realm = realm;
        this.value = value;
        this.check = check;
    }

    public void retain(Fiber dependent) {
        dependents.add(dependent);
    }

    public void release(Fiber dependent) {
        dependents.remove(dependent);
        if (dependents.isEmpty() && drain != null) {
            drain.complete(null);
            drain = null;
        }
    }

    public CompletionStage<Void> drained() {
        if (dependents.isEmpty()) return CompletableFuture.completedFuture(null);
        if (drain == null) drain = new CompletableFuture<>();
        return drain;
    }

    @Override
    public String toString() {
        return "Impl(" + name + "#" + uid + ")";
    }
}
