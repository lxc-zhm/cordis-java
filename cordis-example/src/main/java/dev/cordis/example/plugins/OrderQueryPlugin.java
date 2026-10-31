package dev.cordis.example.plugins;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.service.OrderQueryService;

import java.util.List;

public final class OrderQueryPlugin implements Plugin {
    @Override
    public List<String> inject() {
        return List.of("database");
    }

    @Override
    public Object apply(Context ctx, Object config) {
        new OrderQueryService(ctx, ctx.get("database"));
        return null;
    }
}
