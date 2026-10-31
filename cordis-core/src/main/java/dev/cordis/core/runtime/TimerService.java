package dev.cordis.core.runtime;

import dev.cordis.core.coeffect.ContextualService;
import dev.cordis.core.effect.*;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * Timers and rate-limited callbacks are effects of the CALLING fiber.
 */
public final class TimerService implements ContextualService<TimerService> {
    private final Context context;
    private final ScheduledExecutorService scheduler;

    public TimerService(Context owner) {
        context = owner;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cordis-timer");
            t.setDaemon(true);
            return t;
        });
        owner.effect(c -> (Disposable) scheduler::shutdownNow, "timer scheduler");
        owner.provide("timer", this);
    }

    private TimerService(Context context, ScheduledExecutorService scheduler) {
        this.context = context;
        this.scheduler = scheduler;
    }

    @Override
    public TimerService inContext(Context caller) {
        return new TimerService(caller, scheduler);
    }

    public Disposable timeout(Runnable callback, Duration delay) {
        return schedule(callback, delay, false);
    }

    public Disposable interval(Runnable callback, Duration period) {
        if (period.isZero()) throw new IllegalArgumentException("Zero interval");
        return schedule(callback, period, true);
    }

    private Disposable schedule(Runnable callback, Duration duration, boolean repeat) {
        if (duration.isNegative()) throw new IllegalArgumentException("Negative duration");
        return context.atomic(() -> {
            Disposable[] handle = new Disposable[1];
            handle[0] = context.effect(c -> {
                Runnable guarded = () -> context.atomic(() -> {
                    try {
                        if (context.fiber().uid != null && context.fiber().state != FiberState.UNLOADING)
                            callback.run();
                    } catch (Throwable error) {
                        context.logger("timer").error(error);
                    } finally {
                        if (!repeat) handle[0].dispose();
                    }
                    return null;
                });
                ScheduledFuture<?> future = repeat ? scheduler.scheduleAtFixedRate(guarded, duration.toNanos(), duration.toNanos(), TimeUnit.NANOSECONDS)
                        : scheduler.schedule(guarded, duration.toNanos(), TimeUnit.NANOSECONDS);
                return (Disposable) () -> future.cancel(false);
            }, repeat ? "interval" : "timeout");
            return handle[0];
        });
    }

    public final class ControlledCallback implements Runnable, AutoCloseable {
        private final Runnable callback;
        private final Duration delay;
        private final boolean throttle;
        private Disposable pending;
        private final Disposable ownership;
        private boolean closed;

        private ControlledCallback(Runnable callback, Duration delay, boolean throttle) {
            if (delay.isNegative()) throw new IllegalArgumentException("Negative duration");
            this.callback = callback;
            this.delay = delay;
            this.throttle = throttle;
            ownership = context.effect(c -> (Disposable) () -> {
                closed = true;
                if (pending != null) pending.dispose();
            }, throttle ? "throttle" : "debounce");
        }

        @Override
        public void run() {
            context.atomic(() -> {
                if (closed) return null;
                if (pending != null) {
                    if (throttle) return null;
                    pending.dispose();
                }
                pending = timeout(() -> {
                    pending = null;
                    callback.run();
                }, delay);
                return null;
            });
        }

        @Override
        public void close() {
            ownership.dispose();
        }
    }

    public ControlledCallback debounce(Runnable callback, Duration delay) {
        return new ControlledCallback(callback, delay, false);
    }

    /**
     * Trailing-edge throttle: one invocation per interval, using the first request.
     */
    public ControlledCallback throttle(Runnable callback, Duration delay) {
        return new ControlledCallback(callback, delay, true);
    }
}
