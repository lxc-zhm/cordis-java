package dev.cordis.example.plugins;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.OrderPaid;
import dev.cordis.example.persistence.CommerceDatabase;
import dev.cordis.example.service.FulfillmentService;
import java.util.List;

/** Event subscription is reverted on unload; the durable outbox is replayed on the next activation. */
public final class FulfillmentPlugin implements Plugin {
    @Override public List<String> inject() { return List.of("database"); }
    @Override public Object apply(Context ctx, Object config) {
        CommerceDatabase database = ctx.get("database");
        FulfillmentService fulfillment = new FulfillmentService(ctx, database);
        ctx.onGlobal("order/paid", args -> {
            OrderPaid event = (OrderPaid) args[0];
            if (event.tenantId().equals(database.tenantId())) fulfillment.dispatchPending();
            return null;
        });
        fulfillment.dispatchPending(); return null;
    }
}
