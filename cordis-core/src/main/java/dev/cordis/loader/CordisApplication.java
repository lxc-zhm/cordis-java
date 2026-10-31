package dev.cordis.loader;

import dev.cordis.core.runtime.*;
import dev.cordis.hmr.HotReloadService;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * An owned application combining configuration, optional source HMR and core services.
 */
public final class CordisApplication implements AutoCloseable {
    public final Context context;
    public final ComponentLoader loader;
    private final JavaModuleResolver modules;
    private final ConfigWatcher configs;
    private final HotReloadService hmr;
    private boolean closed;

    private CordisApplication(Context context, ComponentLoader loader, JavaModuleResolver modules, ConfigWatcher configs, HotReloadService hmr) {
        this.context = context;
        this.loader = loader;
        this.modules = modules;
        this.configs = configs;
        this.hmr = hmr;
    }

    public static CordisApplication open(Path config, Path sources, boolean watch) throws Exception {
        Context context = Context.createRoot();
        JavaModuleResolver modules = new JavaModuleResolver(sources);
        ComponentLoader loader = null;
        ConfigWatcher configs = null;
        HotReloadService hmr = null;
        try {
            new TimerService(context);
            loader = ConfigFile.open(context, modules, config);
            if (watch) {
                configs = new ConfigWatcher(loader, config, Duration.ofMillis(150));
                hmr = new HotReloadService(loader, modules, Duration.ofMillis(150));
            }
            return new CordisApplication(context, loader, modules, configs, hmr);
        } catch (Throwable error) {
            if (hmr != null) hmr.close();
            if (configs != null) configs.close();
            if (loader != null) loader.close();
            context.fiber().dispose();
            modules.close();
            throw error;
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        if (hmr != null) hmr.close();
        if (configs != null) configs.close();
        loader.close();
        context.fiber().dispose();
        modules.close();
    }

    /**
     * Usage: CordisApplication config.yaml source-directory [--watch].
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--watch") && !args[2].equals("--check")))
            throw new IllegalArgumentException("Usage: CordisApplication <config.yaml> <source-directory> [--watch|--check]");
        CordisApplication app = open(Path.of(args[0]), Path.of(args[1]), args.length == 3 && args[2].equals("--watch"));
        if (args.length == 3 && args[2].equals("--check")) {
            try (app) {
                for (ComponentLoader branch : app.loader.tree())
                    dev.cordis.core.effect.Stages.join(branch.awaitAllAsync());
            }
            return;
        }
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception error) {
                error.printStackTrace();
            } finally {
                shutdown.countDown();
            }
        }, "cordis-shutdown"));
        shutdown.await();
    }
}
