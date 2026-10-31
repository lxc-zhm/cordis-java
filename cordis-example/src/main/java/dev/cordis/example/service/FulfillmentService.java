package dev.cordis.example.service;

import dev.cordis.core.coeffect.Service;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import java.time.Instant;
import java.util.List;

/** Durable local warehouse adapter. Outbox delivery and shipment creation commit together. */
public final class FulfillmentService extends Service {
    private final CommerceDatabase database;
    public FulfillmentService(Context ctx, CommerceDatabase database) { super(ctx, "fulfillment"); this.database = database; }
    public int dispatchPending() {
        return database.transaction(tx -> {
            int count = 0;
            for (String id : tx.pendingDeliveries()) {
                Order order = tx.order(id);
                if (order.status() != OrderStatus.PAID) continue;
                if (tx.shipment(id) == null) {
                    tx.shipment(new Shipment("shipment-" + id, id, order.request().shippingAddress(), Instant.now())); count++;
                }
                tx.delivered(id);
            }
            return count;
        });
    }
    public List<Shipment> shipments() { return database.read(CommerceDatabase.View::shipments); }
    public int pendingCount() { return database.read(view -> view.pendingDeliveries().size()); }
}
