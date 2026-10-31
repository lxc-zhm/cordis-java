package dev.cordis.example.plugins;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.service.PaymentService;
import java.util.List;

/** The sandbox payment provider can be disabled for maintenance independently of persisted receipts. */
public final class PaymentPlugin implements Plugin {
    @Override public List<String> inject() { return List.of("database"); }
    @Override public Object apply(Context ctx, Object config) { new PaymentService(ctx, ctx.get("database")); return null; }
}
