package dev.cordis.example.service;

import dev.cordis.core.coeffect.Service;
import dev.cordis.core.runtime.Context;
import dev.cordis.example.domain.Commerce.*;
import dev.cordis.example.persistence.CommerceDatabase;
import java.util.*;

/** Inventory changes participate in the SAME transaction as order state changes. */
public final class InventoryService extends Service {
    private final CommerceDatabase database;
    public InventoryService(Context ctx, CommerceDatabase database) { super(ctx, "inventory"); this.database = database; }
    public void seedIfMissing(Product product) {
        database.transaction(tx -> { if (tx.product(product.sku()) == null) tx.product(product); return null; });
    }
    public int available(String sku) { return database.read(view -> require(view, sku).stock()); }
    public List<Product> catalog() { return database.read(CommerceDatabase.View::products); }

    List<OrderLine> lines(CommerceDatabase.View view, List<CartItem> cart) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (CartItem item : cart) quantities.merge(item.sku(), item.quantity(), Math::addExact);
        return quantities.entrySet().stream().map(entry -> {
            Product p = require(view, entry.getKey());
            return new OrderLine(p.sku(), p.name(), entry.getValue(), p.unitPriceCents());
        }).toList();
    }
    void reserve(CommerceDatabase.Transaction tx, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            Product product = require(tx, line.sku());
            if (product.stock() < line.quantity()) throw new BusinessException("INSUFFICIENT_STOCK", "商品库存不足: " + product.sku());
            tx.product(product.withStock(product.stock() - line.quantity()));
        }
    }
    void release(CommerceDatabase.Transaction tx, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            Product product = require(tx, line.sku()); tx.product(product.withStock(Math.addExact(product.stock(), line.quantity())));
        }
    }
    private static Product require(CommerceDatabase.View view, String sku) {
        Product product = view.product(sku);
        if (product == null) throw new BusinessException("UNKNOWN_SKU", "商品不存在: " + sku); return product;
    }
}
