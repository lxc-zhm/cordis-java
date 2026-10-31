package dev.cordis.example.plugins;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.service.OrderService;

import java.util.List;

public final class OrderPlugin implements Plugin {
    @Override
    public List<String> inject() {
        return List.of("database", "inventory", "payments", "pricing");
    }

    @Override
    public Object apply(Context ctx, Object config) {
        OrderService orders = new OrderService(ctx, ctx.get("database"), ctx.get("inventory"), ctx.get("payments"), ctx.get("pricing"));
        orders.recoverPendingPayments();
        return null;
    }
}
