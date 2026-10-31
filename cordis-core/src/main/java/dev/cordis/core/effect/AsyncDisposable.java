package dev.cordis.core.effect;

import java.util.concurrent.CompletionStage;

/**
 * Cleanup whose completion is part of the owning fiber's lifecycle.
 */
@FunctionalInterface
public interface AsyncDisposable extends Disposable {
    CompletionStage<Void> disposeAsync();

    @Override
    default void dispose() {
        disposeAsync().toCompletableFuture().join();
    }
}
