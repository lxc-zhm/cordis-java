package dev.cordis.core.effect;

import java.util.concurrent.CompletionStage;

/**
 * A lazy asynchronous effect iterator. A null step terminates iteration.
 */
@FunctionalInterface
public interface AsyncEffect {
    CompletionStage<Step> next();

    /**
     * The final step may also carry an inverse, as a generator return can.
     */
    record Step(Disposable inverse, boolean done) {
        public static Step yield(Disposable inverse) {
            return new Step(inverse, false);
        }

        public static Step end() {
            return new Step(null, true);
        }
    }
}
