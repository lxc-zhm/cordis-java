package dev.cordis.example.service;

import dev.cordis.core.coeffect.Service;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.Order;
import dev.cordis.example.persistence.CommerceDatabase;
import java.util.List;

/** Read model depends only on persistence, so payment maintenance never takes order history offline. */
public final class OrderQueryService extends Service {
    private final CommerceDatabase database;
    public OrderQueryService(Context ctx, CommerceDatabase database) { super(ctx, "orderQueries"); this.database = database; }
    public Order find(String id) { return database.read(view -> view.order(id)); }
    public List<Order> list() { return database.read(CommerceDatabase.View::orders); }
}
