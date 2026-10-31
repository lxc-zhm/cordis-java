package dev.cordis.example;

import dev.cordis.core.Plugin;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.plugins.*;
import dev.cordis.example.service.*;
import dev.cordis.hmr.HmrEngine;
import dev.cordis.loader.*;
import java.nio.file.Path;
import java.util.*;

/** Tenant-scoped business composition. Public helpers mirror operational actions an admin console would perform. */
public final class CommerceApplication implements AutoCloseable {
    public static final List<String> SERVICES = List.of("database", "inventory", "payments", "pricing", "orders", "orderQueries", "fulfillment");
    public final Context context;
    public final ComponentLoader loader;
    private final DatabasePlugin.Config database;
    private Plugin pricing = new StandardPricingPlugin();
    private StandardPricingPlugin.Config promotion = new StandardPricingPlugin.Config(10000, 1000);
    private boolean paymentEnabled = true, fulfillmentEnabled = true;

    public CommerceApplication(Context root, String tenantId, Path file) {
        Context scope = root;
        for (String service : SERVICES) scope = scope.isolate(service, tenantId);
        context = scope;
        database = new DatabasePlugin.Config(file, tenantId);
        loader = new ComponentLoader(context, url -> switch (url) {
            case "database" -> new DatabasePlugin(); case "inventory" -> new InventoryPlugin();
            case "payments" -> new PaymentPlugin(); case "orders" -> new OrderPlugin();
            case "fulfillment" -> new FulfillmentPlugin(); case "orderQueries" -> new OrderQueryPlugin(); case "pricing" -> pricing;
            default -> throw new IllegalArgumentException("Unknown business plugin: " + url);
        });
        try { reconcile(); } catch (RuntimeException | Error failure) { loader.close(); throw failure; }
    }
    private void reconcile() {
        // Consumers intentionally precede their providers: service declarations drive activation.
        loader.load(List.of(Entry.of("orders", "orders").build(), Entry.of("orderQueries", "orderQueries").build(),
                Entry.of("fulfillment", "fulfillment").disabled(!fulfillmentEnabled).build(),
                Entry.of("inventory", "inventory").build(), Entry.of("payments", "payments").disabled(!paymentEnabled).build(),
                Entry.of("pricing", "pricing").config(promotion).build(), Entry.of("database", "database").config(database).build()));
        for (String id : loader.ids()) if (loader.mounted(id).fiber != null) loader.mounted(id).fiber.await();
    }
    public OrderService orders() { return context.get("orders"); }
    public OrderQueryService queries() { return context.get("orderQueries"); }
    public InventoryService inventory() { return context.get("inventory"); }
    public PaymentService payments() { return context.get("payments"); }
    public FulfillmentService fulfillment() { return context.get("fulfillment"); }
    public OrderService auditOrders() { return context.intercept("orders", Map.of("readOnly", true)).get("orders"); }
    public void paymentEnabled(boolean value) { paymentEnabled = value; reconcile(); }
    public void fulfillmentEnabled(boolean value) { fulfillmentEnabled = value; reconcile(); }
    public void promotion(StandardPricingPlugin.Config value) { promotion = value; reconcile(); }
    public void replacePricing(Plugin replacement) throws Exception {
        HmrEngine.reload(loader, List.of("pricing"), Map.of("pricing", replacement), null); pricing = replacement;
    }
    @Override public void close() { loader.close(); }
}
