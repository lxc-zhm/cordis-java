package dev.cordis.hmr;

import dev.cordis.core.*;
import dev.cordis.core.effect.*;
import dev.cordis.loader.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

/**
 * Watch source trees, debounce edits, compile fresh generations, and atomically restore on failure.
 */
public final class HotReloadService implements AutoCloseable {
    private final ComponentLoader loader;
    private final JavaModuleResolver resolver;
    private final WatchService watcher;
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final Set<Path> changed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cordis-hmr");
        t.setDaemon(true);
        return t;
    });
    private final long debounceMillis;
    private final Thread thread;
    private final Disposable ownership;
    private ScheduledFuture<?> scheduled;
    private volatile boolean closed;
    private final Set<Path> externals;

    public HotReloadService(ComponentLoader loader, JavaModuleResolver resolver, Duration debounce) throws IOException {
        this(loader, resolver, debounce, Set.of());
    }

    public HotReloadService(ComponentLoader loader, JavaModuleResolver resolver, Duration debounce, Set<Path> externals) throws IOException {
        if (debounce.isNegative()) throw new IllegalArgumentException("Negative debounce");
        this.loader = loader;
        this.resolver = resolver;
        this.debounceMillis = debounce.toMillis();
        this.externals = externals.stream().map(p -> Path.of(java.net.URI.create(resolver.url(p)))).collect(java.util.stream.Collectors.toUnmodifiableSet());
        watcher = FileSystems.getDefault().newWatchService();
        registerTree(resolver.sourceRoot());
        ownership = loader.context().effect(c -> (Disposable) this::stop, "HMR watcher");
        thread = Thread.ofVirtual().name("cordis-hmr-watch").start(this::watch);
    }

    private void registerTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path dir : paths.filter(Files::isDirectory).toList())
                directories.put(dir.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE), dir);
        }
    }

    private void watch() {
        try {
            while (!closed) {
                WatchKey key = watcher.take();
                Path directory = directories.get(key);
                if (directory != null) for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        changed.add(resolver.sourceRoot());
                        schedule();
                        continue;
                    }
                    Path file = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(file))
                        registerTree(file);
                    if (file.toString().endsWith(".java")) {
                        changed.add(file);
                        schedule();
                    }
                }
                if (!key.reset()) directories.remove(key);
            }
        } catch (ClosedWatchServiceException ignored) {
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            loader.context().logger("hmr").error(error);
        }
    }

    private synchronized void schedule() {
        if (closed) return;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = executor.schedule(() -> {
            Set<Path> batch = Set.copyOf(changed);
            changed.removeAll(batch);
            if (batch.stream().anyMatch(externals::contains)) {
                loader.context().emit("hmr/restart-required", batch);
                return;
            }
            try {
                reloadNow();
            } catch (Throwable error) {
                loader.context().emit("hmr/error", error);
                loader.context().logger("hmr").error(error);
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    private record Saved(ComponentLoader loader, String id, Plugin old, Plugin fresh) {
    }

    /**
     * Compile/import first, drain all old instances, then install all candidates.
     */
    public synchronized void reloadNow() throws Exception {
        if (closed) throw new IllegalStateException("HMR is closed");
        loader.transaction(() -> {
            reloadTransaction();
            return null;
        });
    }

    private void reloadTransaction() throws Exception {
        List<ComponentLoader.Lease> leases = new ArrayList<>();
        Set<ComponentLoader> acquired = new HashSet<>();
        acquired.add(loader);
        try {
            boolean discovered;
            do {
                discovered = false;
                for (ComponentLoader branch : loader.tree())
                    if (acquired.add(branch)) {
                        leases.add(branch.acquireOperations().toCompletableFuture().get());
                        discovered = true;
                    }
            } while (discovered);
            performReload();
        } finally {
            for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
        }
    }

    private void performReload() throws Exception {
        List<Saved> plans = new ArrayList<>();
        try (JavaModuleResolver.Transaction transaction = resolver.beginReload()) {
            Set<String> sources = resolver.urls();
            for (ComponentLoader branch : loader.tree())
                for (String id : branch.ids()) {
                    var m = branch.mounted(id);
                    if (m.fiber == null || m.entry.disabled) continue;
                    String url = m.entry.url.startsWith("file:") ? m.entry.url : resolver.url(resolver.sourceRoot().resolve(m.entry.url));
                    if (!sources.contains(url)) continue;
                    plans.add(new Saved(branch, id, m.plugin, resolver.resolve(url)));
                }
            try {
                for (Saved saved : plans) saved.loader.detachForReload(saved.id);
                for (Saved saved : plans) saved.loader.remount(saved.id, saved.fresh);
                transaction.commit();
                loader.context().emit("hmr/reload", List.copyOf(plans.stream().map(Saved::id).toList()));
            } catch (Throwable failure) {
                transaction.close();
                for (Saved saved : plans)
                    try {
                        saved.loader.detachForReload(saved.id);
                    } catch (Throwable error) {
                        if (failure != error) failure.addSuppressed(error);
                    }
                for (Saved saved : plans)
                    try {
                        saved.loader.remount(saved.id, saved.old);
                    } catch (Throwable error) {
                        if (failure != error) failure.addSuppressed(error);
                    }
                throw dev.cordis.core.effect.Stages.propagate(failure);
            }
        }
    }

    private void stop() {
        closed = true;
        try {
            watcher.close();
        } catch (IOException error) {
            loader.context().logger("hmr").error(error);
        }
        executor.shutdownNow();
    }

    @Override
    public synchronized void close() {
        stop();
        ownership.dispose();
    }
}
