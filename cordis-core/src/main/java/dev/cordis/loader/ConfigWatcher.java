package dev.cordis.loader;

import dev.cordis.core.effect.Disposable;

import java.nio.file.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Observe the root configuration and dynamically included files. Invalid YAML leaves the old tree running.
 */
public final class ConfigWatcher implements AutoCloseable {
    private final ComponentLoader root;
    private final WatchService watch;
    private final Map<Path, ComponentLoader> files = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final Set<Path> registered = ConcurrentHashMap.newKeySet();
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cordis-config");
        t.setDaemon(true);
        return t;
    });
    private final long delay;
    private final Disposable hook;
    private final Disposable ownership;
    private volatile boolean closed;

    public ConfigWatcher(ComponentLoader root, Path file, Duration debounce) throws IOException {
        if (debounce.isNegative()) throw new IllegalArgumentException("Negative debounce");
        this.root = root;
        delay = debounce.toMillis();
        watch = FileSystems.getDefault().newWatchService();
        register(file, root);
        for (ComponentLoader loader : root.tree()) {
            if (loader.context().fiber().attachment("configPath") instanceof Path included) register(included, loader);
        }
        hook = root.context().onGlobal("loader/config-open", args -> {
            register((Path) args[0], (ComponentLoader) args[1]);
            return null;
        });
        ownership = root.context().effect(c -> (Disposable) this::stop, "config watcher");
        Thread.ofVirtual().name("cordis-config-watch").start(this::run);
    }

    private void register(Path path, ComponentLoader loader) {
        try {
            Path file = path.toRealPath();
            files.put(file, loader);
            if (registered.add(file.getParent())) directories.put(file.getParent().register(watch,
                    StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), file.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void run() {
        try {
            while (!closed) {
                WatchKey key = watch.take();
                Path directory = directories.get(key);
                if (directory != null) for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        for (Path file : files.keySet()) schedule(file);
                        continue;
                    }
                    Path file = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                    if (files.containsKey(file)) schedule(file);
                }
                if (!key.reset()) directories.remove(key);
            }
        } catch (ClosedWatchServiceException ignored) {
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void schedule(Path file) {
        pending.compute(file, (path, old) -> {
            if (old != null) old.cancel(false);
            if (closed) return null;
            return worker.schedule(() -> {
                try {
                    ComponentLoader loader = files.get(file);
                    if (loader.context().fiber().uid == null) {
                        files.remove(file);
                        return;
                    }
                    loader.load(ConfigFile.read(file));
                    root.context().emit("loader/config-reloaded", file);
                } catch (Exception error) {
                    root.context().emit("loader/config-error", file, error);
                    root.context().logger("loader").error(error);
                }
            }, delay, TimeUnit.MILLISECONDS);
        });
    }

    private void stop() {
        closed = true;
        try {
            watch.close();
        } catch (IOException error) {
            root.context().logger("loader").error(error);
        }
        worker.shutdownNow();
        files.clear();
        pending.clear();
    }

    @Override
    public void close() {
        stop();
        hook.dispose();
        ownership.dispose();
    }
}
