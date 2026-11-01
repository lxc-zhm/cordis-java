package dev.cordis.core.coeffect;

import dev.cordis.core.runtime.Context;

/**
 * Java's explicit equivalent of a context-traced service. A view delegates to shared
 * state, but resolves interception from the caller on every operation. Interface
 * services may use {@link Service#bind} instead of implementing a concrete view.
 */
@FunctionalInterface
public interface ContextualService<T> {
    T inContext(Context context);
}
