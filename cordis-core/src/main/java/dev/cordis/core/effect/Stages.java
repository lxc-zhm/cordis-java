package dev.cordis.core.effect;

import java.util.concurrent.*;

/**
 * Small completion helpers shared by lifecycle and effects.
 */
public final class Stages {
    private Stages() {
    }

    public static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    public static Throwable cause(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error;
    }

    public static RuntimeException propagate(Throwable error) {
        error = cause(error);
        if (error instanceof Error fatal) throw fatal;
        return error instanceof RuntimeException runtime ? runtime : new CompletionException(error);
    }

    public static CompletionStage<Void> dispose(Disposable disposable) {
        try {
            if (disposable instanceof AsyncDisposable async) return async.disposeAsync();
            disposable.dispose();
            return done();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public static void join(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
        } catch (CompletionException error) {
            throw propagate(error);
        }
    }
}
