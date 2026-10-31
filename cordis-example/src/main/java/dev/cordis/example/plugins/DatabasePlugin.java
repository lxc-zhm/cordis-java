package dev.cordis.example.plugins;

import dev.cordis.core.Plugin;
import dev.cordis.core.effect.Disposable;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.persistence.CommerceDatabase;
import java.io.*;
import java.nio.file.Path;

/** Opens the tenant's durable database; unloading releases the file lock, never erases business history. */
public final class DatabasePlugin implements Plugin {
    public record Config(Path file, String tenantId) {
        public Config { java.util.Objects.requireNonNull(file); dev.cordis.example.domain.Commerce.requireText(tenantId, "tenant id"); }
    }
    @Override public String name() { return "database"; }
    @Override public Object apply(Context ctx, Object config) {
        Config settings = (Config) config;
        try {
            CommerceDatabase database = CommerceDatabase.open(settings.file(), settings.tenantId());
            ctx.effect(c -> (Disposable) () -> {
                try { database.close(); } catch (IOException error) { throw new UncheckedIOException(error); }
            }, "tenant database session");
            ctx.provide("database", database);
        } catch (IOException error) { throw new UncheckedIOException(error); }
        return null;
    }
}
