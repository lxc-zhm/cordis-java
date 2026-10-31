package dev.cordis.loader;

import dev.cordis.core.*;
import dev.cordis.core.effect.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Group/include are ordinary plugins; their child loaders are owned effects.
 */
public final class BuiltinPlugins {
    private BuiltinPlugins() {
    }

    public static PluginResolver resolver(PluginResolver modules, Path base) {
        return resolver(modules, base, Set.of());
    }

    private static PluginResolver resolver(PluginResolver modules, Path base, Set<Path> ancestors) {
        return url -> switch (url) {
            case "group", "@cordis/group" -> group(resolver(modules, base, ancestors));
            case "include", "@cordis/include" -> include(modules, base, ancestors);
            default -> modules.resolve(url);
        };
    }

    public static Plugin group(PluginResolver resolver) {
        return Plugin.of("group", List.of(), (ctx, config) -> {
            ComponentLoader children = new ComponentLoader(ctx, resolver);
            ctx.fiber().attach("loader", children);
            children.onChange(entries -> ctx.fiber().commitConfig(entries));
            ctx.on("internal/update", args -> {
                Object nextConfig = args[0];
                return children.reconcileAsync(ConfigFile.entries(nextConfig)).thenRun(() -> ctx.fiber().commitConfig(nextConfig));
            });
            return children.reconcileAsync(ConfigFile.entries(config)).thenApply(v -> (AsyncDisposable) children::disposeAllAsync);
        });
    }

    private static Plugin include(PluginResolver modules, Path base, Set<Path> ancestors) {
        return Plugin.of("include", List.of(), (ctx, config) -> {
            try {
                if (!(config instanceof String filename))
                    throw new IllegalArgumentException("include config must be a filename");
                Path file = base.resolve(filename).toRealPath();
                if (ancestors.contains(file)) throw new IllegalArgumentException("Circular include: " + file);
                Set<Path> chain = new HashSet<>(ancestors);
                chain.add(file);
                ComponentLoader nested = new ComponentLoader(ctx, resolver(modules, file.getParent(), Set.copyOf(chain)));
                ctx.fiber().attach("loader", nested);
                ctx.fiber().attach("configPath", file);
                ctx.emit("loader/config-open", file, nested);
                nested.onChange(entries -> {
                    try {
                        ConfigFile.write(file, entries);
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
                return nested.reconcileAsync(ConfigFile.read(file)).thenApply(v -> (AsyncDisposable) nested::disposeAllAsync);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }
}
